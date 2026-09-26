// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInput
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.ShotDistanceEstimate
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The range's state holder, a port of `DrivingRangeViewModel.swift`'s phase machine.
 *
 * - The shot on screen when the range opens is shown without flying it; each later shot from
 *   [ShotRepository.latestShot] flies.
 * - A shot that arrives while one is preparing, flying or landed is queued, and only the newest
 *   queued shot flies after the current one lands.
 * - The landing dwells for [LANDING_DWELL_MILLIS] before the next shot or [RangePhase.Waiting].
 * - [suspend] (background, screen gone) cancels everything and returns to waiting.
 * - The camera mode (plan R7a) is the persisted [SettingsRepository.rangeCameraMode], forced to
 *   [RangeCameraMode.FIXED] while the platform asks for reduced motion.
 *
 * Plan F8a1 adds two modes on top of that live behaviour ([RangeBrowseState]):
 * - [RangeMode.Replay] flies a stored session's shots through the same phase machine, oldest
 *   first, auto-advancing after each landing dwell while playing, at 0.5×/1×/2×.
 * - [RangeMode.Overlay] draws up to [RangeBrowseState.OVERLAY_CAP] stored shots at once; each
 *   trajectory is simulated once on [computeDispatcher] and cached by the stored row id.
 * Entering either pauses live playback. A live shot arriving meanwhile never pulls the user out:
 * it raises [RangeBrowseState.newLiveShot], and [DrivingRangeEvent.ReturnToLive] flies it.
 *
 * The simulation runs on [computeDispatcher] (`Dispatchers.Default`); tests inject a test
 * dispatcher so the dwell and the simulation run on virtual time.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One phase machine plus its replay/overlay modes.
class DrivingRangeViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val history: ShotHistoryRepository,
    private val conditions: ConditionsRepository,
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val simulation: (FlightInput) -> FlightTrajectory = BallFlightSimulator()::simulate,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val distanceEstimate: (FlightMeasurements, Conditions, TargetBearing?) -> ShotDistanceEstimate? =
        ShotDistanceEstimator()::estimate,
) : ViewModel() {
    private val initialShot = shots.latestShot.value
    private val flight = MutableStateFlow(FlightState(phase = RangePhase.Waiting, displayedShot = initialShot))
    private val clubRequest = MutableStateFlow(ClubRequest())
    private val reduceMotion = MutableStateFlow(false)
    private val browse = MutableStateFlow(RangeBrowseState())
    private val camera =
        combine(settings.rangeCameraMode, reduceMotion) { preferred, reduced ->
            RangeCameraState(mode = if (reduced) RangeCameraMode.FIXED else preferred, locked = reduced)
        }
    private val clubState =
        combine(settings.selectedClub, shots.connectionState, clubRequest) { club, connection, request ->
            RangeClubState(
                selected = club,
                selectionEnabled = connection == ConnectionState.Connected,
                isChanging = request.inFlight,
                error = request.error,
            )
        }

    private var lastObservedEventId: String? = initialShot?.eventId
    private var pendingShot: ShotEvent? = null
    private var preparationJob: Job? = null
    private var landingJob: Job? = null

    /** Bumped by every preparation and by [suspend], so a stale simulation never lands. */
    private var generation = 0L

    /** The live shot that arrived while replaying or overlaying; [DrivingRangeEvent.ReturnToLive] flies it. */
    private var pendingLiveShot: ShotEvent? = null
    private var browseJob: Job? = null
    private var replayShots: List<HistoryShot> = emptyList()
    private var overlayShots: List<HistoryShot> = emptyList()

    /** Stored shots' Pi details by the event id they fly under, so roll-out uses the spin-adjusted carry. */
    private val historyDetails = mutableMapOf<String, ShotDetail>()

    /** Overlay trajectories by stored row id (`null`: the shot can't be simulated). Main thread only. */
    private val overlayCache = mutableMapOf<Long, FlightTrajectory?>()

    val uiState: StateFlow<DrivingRangeUiState> =
        combine(flight, clubState, camera, browse) { flight, club, camera, browse ->
            val shot = flight.displayedShot
            if (shot == null) {
                DrivingRangeUiState.Ready(club, camera, browse)
            } else {
                DrivingRangeUiState.Showing(
                    shot,
                    flight.phase,
                    flight.activeFlight,
                    club,
                    camera,
                    browse,
                    flight.rollOut,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = initialState(),
        )

    init {
        viewModelScope.launch { shots.latestShot.collect(::observe) }
        viewModelScope.launch {
            history.sessions().collect { sessions ->
                browse.update { state -> state.copy(sessions = sessions.map(RangeSessionOption::of)) }
            }
        }
    }

    fun onEvent(event: DrivingRangeEvent) {
        when (event) {
            DrivingRangeEvent.Replay -> replayDisplayedShot()
            DrivingRangeEvent.FlightCompleted -> animationCompleted()
            is DrivingRangeEvent.ClubSelected -> changeClub(event.club)
            DrivingRangeEvent.ToggleCameraMode -> toggleCameraMode()
            is DrivingRangeEvent.ReduceMotionChanged -> reduceMotion.value = event.enabled
            else -> onBrowseEvent(event)
        }
    }

    /** Cancels any preparation, flight and dwell, drops the queued shot and returns to waiting. */
    fun suspend() {
        stopFlight()
        pendingShot = null
        flight.value = flight.value.copy(phase = RangePhase.Waiting, activeFlight = null)
        browse.update { it.copy(playing = false) }
    }

    private fun onBrowseEvent(event: DrivingRangeEvent) {
        when (event) {
            is DrivingRangeEvent.StartReplay -> startReplay(event.sessionId, event.index)
            is DrivingRangeEvent.StartOverlay -> startOverlay(event.sessionId, event.club)
            is DrivingRangeEvent.SetOverlayClub -> setOverlayClub(event.club)
            DrivingRangeEvent.ReturnToLive -> returnToLive()
            DrivingRangeEvent.PlayPause -> playPause()
            DrivingRangeEvent.NextShot -> stepReplay(1)
            DrivingRangeEvent.PreviousShot -> stepReplay(-1)
            is DrivingRangeEvent.SetSpeed -> browse.update { it.copy(speed = event.speed) }
            is DrivingRangeEvent.SelectShot -> selectShot(event.shotId)
            is DrivingRangeEvent.ViewChanged -> browse.update { it.copy(view = event.view) }
            DrivingRangeEvent.ResetView -> browse.update { it.copy(view = ViewTransform.IDENTITY) }
            else -> Unit
        }
    }

    private fun observe(shot: ShotEvent?) {
        if (shot == null || shot.eventId == lastObservedEventId) return
        lastObservedEventId = shot.eventId
        if (!browse.value.isLive) {
            // Plan F8a: never yank the user out of replay or overlay; offer the way back instead.
            pendingLiveShot = shot
            browse.update { it.copy(newLiveShot = true) }
            return
        }
        when (flight.value.phase) {
            RangePhase.Preparing, RangePhase.Flying, RangePhase.Landed -> pendingShot = shot
            RangePhase.Waiting, is RangePhase.Unavailable -> prepare(shot)
        }
    }

    private fun replayDisplayedShot() {
        val shot = flight.value.displayedShot ?: return
        val phase = flight.value.phase
        if (phase == RangePhase.Preparing || phase == RangePhase.Flying) return
        pendingShot = null
        prepare(shot)
    }

    private fun animationCompleted() {
        if (flight.value.phase != RangePhase.Flying) return
        flight.value = flight.value.copy(phase = RangePhase.Landed)
        landingJob?.cancel()
        val dwell = (LANDING_DWELL_MILLIS / playbackSpeed()).toLong()
        landingJob =
            viewModelScope.launch {
                delay(dwell)
                advanceAfterLanding()
            }
    }

    /** Resolving is a cheap table lookup, so it runs here, like the reference; the RK4 integration doesn't. */
    private fun prepare(shot: ShotEvent) {
        preparationJob?.cancel()
        landingJob?.cancel()
        landingJob = null

        val input =
            try {
                resolver.resolve(shot)
            } catch (error: FlightInputResolutionError) {
                generation++
                flight.value = FlightState(RangePhase.Unavailable(error.message.orEmpty()), shot, activeFlight = null)
                return
            }

        flight.value = FlightState(RangePhase.Preparing, shot, activeFlight = null)
        val current = ++generation
        val speed = playbackSpeed()
        val measurements = measurementsFor(shot)
        val air = conditions.conditions.value
        val bearing = conditions.targetBearing.value
        preparationJob =
            viewModelScope.launch {
                // The roll-out estimate lands with the trajectory, so a flight is one state change.
                val (trajectory, rollOut) =
                    withContext(computeDispatcher) {
                        simulation(input) to distanceEstimate(measurements, air, bearing)?.toRollOut()
                    }
                if (generation != current) return@launch
                flight.value =
                    flight.value.copy(
                        phase = RangePhase.Flying,
                        activeFlight = ActiveFlight(trajectory, current, speed),
                        rollOut = rollOut,
                    )
                preparationJob = null
            }
    }

    private fun advanceAfterLanding() {
        landingJob = null
        flight.value = flight.value.copy(activeFlight = null)
        val mode = browse.value.mode
        if (mode is RangeMode.Replay) {
            val hasNext = mode.index + 1 < replayShots.size
            if (browse.value.playing && hasNext) {
                showReplayShot(mode.index + 1)
            } else {
                browse.update { it.copy(playing = it.playing && hasNext) }
                flight.value = flight.value.copy(phase = RangePhase.Waiting)
            }
            return
        }
        val next = pendingShot
        if (next != null) {
            pendingShot = null
            prepare(next)
        } else {
            flight.value = flight.value.copy(phase = RangePhase.Waiting)
        }
    }

    // region Plan F8a1: replay and overlay.

    /** Replay flies at the chosen speed; live and overlay shots at 1×. */
    private fun playbackSpeed(): Double = if (browse.value.mode is RangeMode.Replay) browse.value.speed.factor else 1.0

    /** Cancels the preparation, flight and dwell (and makes any in-flight simulation stale). */
    private fun stopFlight() {
        generation++
        preparationJob?.cancel()
        landingJob?.cancel()
        preparationJob = null
        landingJob = null
    }

    /** Leaving live: park its queued shot as the one to fly on return. */
    private fun leaveLive() {
        stopFlight()
        browseJob?.cancel()
        if (browse.value.isLive) {
            pendingShot?.let { pendingLiveShot = it }
            pendingShot = null
        }
    }

    private fun startReplay(
        sessionId: String,
        index: Int,
    ) {
        leaveLive()
        browse.update {
            it.copy(
                mode = RangeMode.Replay(sessionId, index),
                loading = true,
                playing = true,
                shots = emptyList(),
                selectedShotId = null,
                overlayFlights = emptyList(),
                overlayTruncated = false,
                overlayClubs = emptyList(),
                newLiveShot = pendingLiveShot != null,
            )
        }
        flight.value = FlightState(RangePhase.Waiting, displayedShot = null)
        browseJob =
            viewModelScope.launch {
                // Oldest first: the order the session was hit in.
                val stored =
                    history
                        .shots(sessionId)
                        .first()
                        .filter { it.toRangeShotEvent() != null }
                        .sortedWith(compareBy<HistoryShot>({ it.detail.timestamp }, { it.id }))
                replayShots = stored
                browse.update {
                    it.copy(loading = false, shots = stored.mapIndexed { i, shot -> shot.toRangeShotItem(i + 1) })
                }
                if (stored.isEmpty()) {
                    browse.update { it.copy(playing = false) }
                } else {
                    showReplayShot(index.coerceIn(0, stored.lastIndex))
                }
            }
    }

    /** Shows and flies replay shot [index]. */
    private fun showReplayShot(index: Int) {
        val mode = browse.value.mode as? RangeMode.Replay
        val stored = replayShots.getOrNull(index)
        val shot = stored?.toRangeShotEvent()
        if (mode == null || stored == null || shot == null) return
        historyDetails[shot.eventId] = stored.detail
        browse.update { it.copy(mode = mode.copy(index = index), selectedShotId = stored.id.toString()) }
        pendingShot = null
        prepare(shot)
    }

    private fun stepReplay(delta: Int) {
        val mode = browse.value.mode as? RangeMode.Replay ?: return
        val target = (mode.index + delta).coerceIn(0, (replayShots.size - 1).coerceAtLeast(0))
        if (target != mode.index) showReplayShot(target)
    }

    /** Pause stops the auto-advance; play continues with the next shot (from the start after the last). */
    private fun playPause() {
        val mode = browse.value.mode as? RangeMode.Replay ?: return
        if (browse.value.playing) {
            browse.update { it.copy(playing = false) }
            return
        }
        browse.update { it.copy(playing = true) }
        if (flight.value.phase == RangePhase.Waiting && replayShots.isNotEmpty()) {
            showReplayShot(if (mode.index + 1 < replayShots.size) mode.index + 1 else 0)
        }
    }

    private fun startOverlay(
        sessionId: String?,
        club: String?,
    ) {
        leaveLive()
        browse.update {
            it.copy(
                mode = RangeMode.Overlay(sessionId, club),
                loading = true,
                playing = false,
                shots = emptyList(),
                selectedShotId = null,
                overlayFlights = emptyList(),
                overlayTruncated = false,
                newLiveShot = pendingLiveShot != null,
            )
        }
        flight.value = FlightState(RangePhase.Waiting, displayedShot = null)
        browseJob =
            viewModelScope.launch {
                val candidates = loadOverlayCandidates(sessionId, club)
                val clubs = orderClubs(candidates.mapNotNull { shot -> shot.detail.club?.takeIf { it.isNotEmpty() } })
                val matching = if (club == null) candidates else candidates.filter { it.detail.club == club }
                val capped = matching.take(RangeBrowseState.OVERLAY_CAP)
                val missing = capped.filter { it.id !in overlayCache }
                if (missing.isNotEmpty()) {
                    val computed =
                        withContext(computeDispatcher) { missing.associate { it.id to overlayTrajectory(it) } }
                    overlayCache.putAll(computed)
                }
                val flights =
                    capped.mapNotNull { shot ->
                        val trajectory = overlayCache[shot.id] ?: return@mapNotNull null
                        val wire = shot.detail.club.orEmpty()
                        OverlayFlight(shot.id.toString(), wire, clubs.indexOf(wire).coerceAtLeast(0), trajectory)
                    }
                overlayShots = capped
                browse.update {
                    it.copy(
                        loading = false,
                        shots = capped.mapIndexed { i, shot -> shot.toRangeShotItem(i + 1) },
                        overlayFlights = flights,
                        overlayTruncated = matching.size > RangeBrowseState.OVERLAY_CAP,
                        overlayClubs = clubs,
                    )
                }
            }
    }

    /**
     * Flyable stored shots, newest first: one session's, or every session's, newest session first,
     * read until more than the cap match [club] (so the "more than 200" note is right).
     */
    private suspend fun loadOverlayCandidates(
        sessionId: String?,
        club: String?,
    ): List<HistoryShot> {
        val flyable: (HistoryShot) -> Boolean = { it.toRangeShotEvent() != null }
        if (sessionId != null) return history.shots(sessionId).first().filter(flyable)
        val collected = mutableListOf<HistoryShot>()
        var matching = 0
        for (session in history.sessions().first()) {
            val sessionShots = history.shots(session.id).first().filter(flyable)
            collected += sessionShots
            matching += sessionShots.count { club == null || it.detail.club == club }
            if (matching > RangeBrowseState.OVERLAY_CAP) break
        }
        return collected
    }

    /** Runs on [computeDispatcher]: touches no view-model state. */
    private fun overlayTrajectory(stored: HistoryShot): FlightTrajectory? {
        val input =
            stored.toRangeShotEvent()?.let { shot ->
                try {
                    resolver.resolve(shot)
                } catch (_: FlightInputResolutionError) {
                    null
                }
            }
        return input?.let { simulation(it).downsampled(RangeBrowseState.OVERLAY_TRAJECTORY_POINTS) }
    }

    private fun setOverlayClub(club: String?) {
        val mode = browse.value.mode as? RangeMode.Overlay ?: return
        if (mode.club != club) startOverlay(mode.sessionId, club)
    }

    private fun selectShot(shotId: String) {
        when (browse.value.mode) {
            is RangeMode.Replay -> {
                val index = replayShots.indexOfFirst { it.id.toString() == shotId }
                if (index >= 0) showReplayShot(index)
            }

            is RangeMode.Overlay -> {
                val stored = overlayShots.firstOrNull { it.id.toString() == shotId } ?: return
                val shot = stored.toRangeShotEvent() ?: return
                historyDetails[shot.eventId] = stored.detail
                stopFlight()
                browse.update { it.copy(selectedShotId = shotId) }
                flight.value = FlightState(RangePhase.Waiting, shot)
                val measurements = measurementsFor(shot)
                val air = conditions.conditions.value
                val bearing = conditions.targetBearing.value
                val current = generation
                preparationJob =
                    viewModelScope.launch {
                        val rollOut =
                            withContext(computeDispatcher) { distanceEstimate(measurements, air, bearing) }?.toRollOut()
                        if (generation == current && flight.value.displayedShot == shot) {
                            flight.value = flight.value.copy(rollOut = rollOut)
                        }
                    }
            }

            RangeMode.Live -> {
                Unit
            }
        }
    }

    /** Back to live: fly the shot that arrived meanwhile, or show the latest one without flying it. */
    private fun returnToLive() {
        if (browse.value.isLive) return
        browseJob?.cancel()
        stopFlight()
        replayShots = emptyList()
        overlayShots = emptyList()
        browse.update { RangeBrowseState(sessions = it.sessions, speed = it.speed) }
        val live = pendingLiveShot
        pendingLiveShot = null
        if (live != null) {
            prepare(live)
        } else {
            flight.value = FlightState(RangePhase.Waiting, shots.latestShot.value)
        }
    }

    // endregion

    /** Persists the other camera; the settings flow brings it back into the state. Locked under reduced motion. */
    private fun toggleCameraMode() {
        if (reduceMotion.value) return
        viewModelScope.launch {
            val next =
                when (settings.rangeCameraMode.first()) {
                    RangeCameraMode.FIXED -> RangeCameraMode.FOLLOW
                    RangeCameraMode.FOLLOW -> RangeCameraMode.FIXED
                }
            settings.setRangeCameraMode(next)
        }
    }

    /** ContentView.swift `changeClub(to:)`: one request at a time; the repository persists the Pi's answer. */
    @Suppress("TooGenericExceptionCaught") // Any failure is shown in the overlay, like the reference.
    private fun changeClub(club: GolfClub) {
        if (clubRequest.value.inFlight) return
        clubRequest.value = ClubRequest(inFlight = true)
        viewModelScope.launch {
            val error =
                try {
                    shots.setClub(club)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failure.message ?: CLUB_CHANGE_FAILED
                }
            clubRequest.value = ClubRequest(inFlight = false, error = error)
        }
    }

    private fun initialState(): DrivingRangeUiState =
        initialShot?.let { DrivingRangeUiState.Showing(it, RangePhase.Waiting, activeFlight = null) }
            ?: DrivingRangeUiState.Ready()

    /** The stored shot's Pi detail when it has one (its spin-adjusted carry anchors the estimate). */
    private fun measurementsFor(shot: ShotEvent): FlightMeasurements =
        historyDetails[shot.eventId]?.toFlightMeasurements() ?: shot.toFlightMeasurements()

    /**
     * @property rollOut the displayed shot's estimated roll-out: computed with each flight, and for
     *   a selected overlay shot.
     */
    private data class FlightState(
        val phase: RangePhase,
        val displayedShot: ShotEvent?,
        val activeFlight: ActiveFlight? = null,
        val rollOut: RangeRollOut? = null,
    )

    private data class ClubRequest(
        val inFlight: Boolean = false,
        val error: String? = null,
    )

    companion object {
        /** How long the landed ball stays on screen before the next shot (DrivingRangeViewModel.swift). */
        const val LANDING_DWELL_MILLIS = 1_250L
        const val CLUB_CHANGE_FAILED = "Couldn't change the club."
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun ShotDistanceEstimate.toRollOut(): RangeRollOut =
    RangeRollOut(
        carryYards = carryYards,
        rollYards = rollYards,
        totalYards = totalYards,
        carryEstimated = isAdjusted,
    )
