// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.PlannedShot
import dev.openflight.companion.core.flight.ShotDistanceEstimate
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.ShotFlightPlanner
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Plan F8f adds the quick settings: each control persists through the key Settings › Practice
 * uses, and "Show" maps onto the overlay (the current session's newest N or all of it, following its
 * new live shots, or every session), restored when the range next opens.
 *
 * The simulation runs on [computeDispatcher] (`Dispatchers.Default`); tests inject a test
 * dispatcher so the dwell and the simulation run on virtual time.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class DrivingRangeViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val history: ShotHistoryRepository,
    private val conditions: ConditionsRepository,
    private val piSession: PiSessionRepository,
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val flightPlan: (FlightMeasurements, Conditions, TargetBearing?) -> PlannedShot? =
        ShotFlightPlanner()::plan,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val distanceEstimate: (FlightMeasurements, Conditions, TargetBearing?) -> ShotDistanceEstimate? =
        ShotDistanceEstimator()::estimate,
) : ViewModel() {
    private val initialShot = shots.latestShot.value
    private val flight = MutableStateFlow(FlightState(phase = RangePhase.Waiting, displayedShot = initialShot))
    private val clubRequest = MutableStateFlow(ClubRequest())
    private val reduceMotion = MutableStateFlow(false)
    private val browse = MutableStateFlow(RangeBrowseState())

    /** Plan F8a2t: the live flights flown before the current one, newest first (at most [MAX_PRIOR_FLIGHTS]). */
    private val priorFlights = MutableStateFlow<List<ActiveFlight>>(emptyList())
    private var lastLiveFlight: ActiveFlight? = null

    private val trail =
        combine(
            settings.shotTrail,
            settings.shotTrailKeepLast,
            settings.landingEffect,
            priorFlights,
            browse,
        ) { style, keepLast, effect, priors, browse ->
            RangeTrailState(
                style = style,
                keepLast = keepLast,
                landingEffect = effect,
                // Plan F8a2t: earlier trails only in live; replay and the overlay draw none.
                priorFlights = if (browse.isLive) priors.take(keepLast) else emptyList(),
            )
        }.distinctUntilChanged()

    /** Plan F8f: the units and "Show total + roll (est.)", shared with Settings › Practice. */
    private val numbers = combine(settings.units, settings.showTotalDistance, ::RangeNumbers).distinctUntilChanged()
    private val camera =
        combine(
            settings.rangeCameraMode,
            reduceMotion,
            settings.rangeTheme,
            trail,
            numbers,
        ) { preferred, reduced, theme, trail, numbers ->
            RangeCameraState(
                mode = if (reduced) RangeCameraMode.FIXED else preferred,
                locked = reduced,
                theme = RangeTheme.of(theme),
                trail = trail,
                numbers = numbers,
            )
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

    /** Plan F8f: a mode was asked for (a launch, History, Show, Live), so the stored "Show" isn't applied. */
    private var modeRequested = false
    private var replayShots: List<HistoryShot> = emptyList()
    private var overlayShots: List<HistoryShot> = emptyList()

    /** Stored shots' Pi details by the event id they fly under, so roll-out uses the spin-adjusted carry. */
    private val historyDetails = mutableMapOf<String, ShotDetail>()

    /** Overlay trajectories by stored row id (`null`: the shot can't be simulated). Main thread only. */
    private val overlayCache = mutableMapOf<Long, FlightTrajectory?>()

    /** Plan F8d: why the last simulate request failed. */
    private val simulateError = MutableStateFlow<String?>(null)

    /** Session's and Games' rule: only a `--mock` Pi over a connected Socket.IO link simulates. */
    private val simulate =
        combine(piSession.mockMode, piSession.linkState, simulateError) { mock, link, error ->
            SimulateState(available = mock == true && PiFeatureAvailability.of(link).isAvailable, error = error)
        }

    val uiState: StateFlow<DrivingRangeUiState> =
        combine(flight, clubState, camera, browse, simulate) { flight, club, camera, browse, simulate ->
            val shot = flight.displayedShot
            if (shot == null) {
                DrivingRangeUiState.Ready(club, camera, browse, simulate.available, simulate.error)
            } else {
                DrivingRangeUiState.Showing(
                    shot,
                    flight.phase,
                    flight.activeFlight,
                    club,
                    camera,
                    browse,
                    flight.rollOut?.shownWith(camera.numbers),
                    simulate.available,
                    simulate.error,
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
        viewModelScope.launch {
            // Plan F8f: reopen on the last "Show" choice, unless the screen already asked for a mode.
            val stored = settings.rangeShow.first()
            if (stored != RangeShowSetting.LIVE && !modeRequested) applyShow(stored)
        }
        viewModelScope.launch {
            combine(settings.viewingProfile, piSession.profiles, RangeProfileState::of)
                .distinctUntilChanged()
                .collect(::applyProfiles)
        }
    }

    /** The newest live shot the viewing profile shows (plan F8f), or `null`. */
    private fun latestShownShot(): ShotEvent? {
        val latest = shots.latestShot.value
        return if (latest == null || profileFilter.shows(latest.profileId)) {
            latest
        } else {
            shots.history.value.firstOrNull { profileFilter.shows(it.profileId) }
        }
    }

    /** The profile filter in force (plan F8f); everyone until the roster and the choice are known. */
    private val profileFilter: RangeProfileState get() = browse.value.profiles

    /**
     * Plan F8f: a new "Viewing profile" or roster. When it changes whose shots show, the kept
     * trails go, a live shot of someone else's leaves the screen (for this profile's latest), and a
     * replay or overlay reloads with this profile's shots.
     */
    private fun applyProfiles(profiles: RangeProfileState) {
        val changed = profiles.filterProfileId != profileFilter.filterProfileId
        if (changed) {
            // Cleared first, so no state shows the new profile with the old one's trails.
            priorFlights.value = emptyList()
            lastLiveFlight = null
        }
        browse.update { it.copy(profiles = profiles) }
        if (!changed) return
        when (val mode = browse.value.mode) {
            RangeMode.Live -> {
                val shown = flight.value.displayedShot
                if (shown != null && !profiles.shows(shown.profileId)) {
                    stopFlight()
                    pendingShot = null
                    flight.value = FlightState(RangePhase.Waiting, latestShownShot())
                }
            }

            is RangeMode.Overlay -> {
                startOverlay(mode.sessionId, mode.club, mode.limit, browse.value.show)
            }

            is RangeMode.Replay -> {
                startReplay(mode.sessionId, index = 0)
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
            is DrivingRangeEvent.Launch -> launch(event.launch)
            DrivingRangeEvent.SimulateShot -> simulateShot()
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
            is DrivingRangeEvent.StartReplay -> {
                modeRequested = true
                startReplay(event.sessionId, event.index)
            }

            is DrivingRangeEvent.StartOverlay -> {
                modeRequested = true
                startOverlay(event.sessionId, event.club)
            }

            is DrivingRangeEvent.SetOverlayClub -> {
                setOverlayClub(event.club)
            }

            DrivingRangeEvent.ReturnToLive -> {
                modeRequested = true
                returnToLive()
            }

            DrivingRangeEvent.PlayPause -> {
                playPause()
            }

            DrivingRangeEvent.NextShot -> {
                stepReplay(1)
            }

            DrivingRangeEvent.PreviousShot -> {
                stepReplay(-1)
            }

            is DrivingRangeEvent.SetSpeed -> {
                browse.update { it.copy(speed = event.speed) }
            }

            is DrivingRangeEvent.SelectShot -> {
                selectShot(event.shotId)
            }

            is DrivingRangeEvent.ViewChanged -> {
                browse.update { it.copy(view = event.view) }
            }

            DrivingRangeEvent.ResetView -> {
                browse.update { it.copy(view = ViewTransform.IDENTITY) }
            }

            else -> {
                onQuickSettingsEvent(event)
            }
        }
    }

    /**
     * Plan F8f: the quick settings panel. Every control persists through the key Settings ›
     * Practice uses; the settings flows then bring the change into the state, so both stay in sync
     * and the scene updates without leaving the range.
     */
    private fun onQuickSettingsEvent(event: DrivingRangeEvent) {
        when (event) {
            is DrivingRangeEvent.SetShow -> {
                setShow(event.show)
            }

            is DrivingRangeEvent.SetTrailStyle -> {
                persist { settings.setShotTrail(event.style) }
            }

            is DrivingRangeEvent.SetTrailKeepLast -> {
                persist { settings.setShotTrailKeepLast(event.count) }
            }

            is DrivingRangeEvent.SetLandingEffect -> {
                persist { settings.setLandingEffect(event.effect) }
            }

            is DrivingRangeEvent.SetTheme -> {
                persist { settings.setRangeTheme(event.theme) }
            }

            is DrivingRangeEvent.SetCameraMode -> {
                if (!reduceMotion.value) {
                    persist {
                        settings.setRangeCameraMode(
                            event.mode,
                        )
                    }
                }
            }

            is DrivingRangeEvent.SetUnits -> {
                persist { settings.setUnits(event.units) }
            }

            is DrivingRangeEvent.SetShowTotal -> {
                persist { settings.setShowTotalDistance(event.show) }
            }

            // Device-local: never `set_active_profile`, which would switch every phone on the Pi.
            is DrivingRangeEvent.SetViewingProfile -> {
                persist { settings.setViewingProfile(event.profile) }
            }

            else -> {
                Unit
            }
        }
    }

    private fun persist(write: suspend () -> Unit) {
        viewModelScope.launch { write() }
    }

    /** Plan F8f: "Show" persists the choice and switches to it now. */
    private fun setShow(show: RangeShowSetting) {
        modeRequested = true
        persist { settings.setRangeShow(show) }
        applyShow(show)
    }

    /**
     * Maps a "Show" choice onto the browse modes: [RangeShowSetting.LIVE] is live; LAST N and THIS
     * SESSION overlay the current session (newest N, or all of it), following its new shots; ALL
     * SESSIONS overlays every session. The overlay's club filter carries over.
     */
    private fun applyShow(show: RangeShowSetting) {
        val club = (browse.value.mode as? RangeMode.Overlay)?.club
        when (show) {
            RangeShowSetting.LIVE -> returnToLive()
            RangeShowSetting.ALL_SESSIONS -> startOverlay(sessionId = null, club = club, show = show)
            else -> startOverlay(sessionId = null, club = club, limit = show.lastShots, show = show)
        }
    }

    private fun observe(shot: ShotEvent?) {
        if (shot == null || shot.eventId == lastObservedEventId) return
        lastObservedEventId = shot.eventId
        if (profileFilter.shows(shot.profileId)) observeShown(shot)
    }

    /** A live shot the viewing profile shows (plan F8f: another profile's neither flies nor shows). */
    private fun observeShown(shot: ShotEvent) {
        if (browse.value.followsLiveShots) {
            // Plan F8f: "Last N" / "This session" keep hitting: the shot flies over the overlay,
            // which picks it up from the session's history.
            browse.update { it.copy(selectedShotId = null) }
        } else if (!browse.value.isLive) {
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

        val measurements = measurementsFor(shot)
        val spinRpm =
            try {
                resolver.resolve(measurements).spinRpm
            } catch (error: FlightInputResolutionError) {
                generation++
                flight.value = FlightState(RangePhase.Unavailable(error.message.orEmpty()), shot, activeFlight = null)
                return
            }

        flight.value = FlightState(RangePhase.Preparing, shot, activeFlight = null)
        val current = ++generation
        val speed = playbackSpeed()
        val air = conditions.conditions.value
        val bearing = conditions.targetBearing.value
        preparationJob =
            viewModelScope.launch {
                // Plan F2b: one plan gives the roll-out estimate and the flight drawn to land at its
                // carry, so a flight is one state change and the conditions run isn't flown twice.
                val plan = withContext(computeDispatcher) { flightPlan(measurements, air, bearing) }
                if (generation != current) return@launch
                flight.value =
                    if (plan == null) {
                        FlightState(RangePhase.Unavailable(FLIGHT_UNAVAILABLE), shot, activeFlight = null)
                    } else {
                        val active =
                            ActiveFlight(
                                plan.trajectory,
                                current,
                                speed,
                                spinRpm = spinRpm,
                                clubColorIndex = GolfClub.fromWireValue(shot.club)?.ordinal ?: 0,
                            )
                        rememberLiveFlight(active)
                        flight.value.copy(
                            phase = RangePhase.Flying,
                            activeFlight = active,
                            rollOut = plan.estimate.toRollOut(),
                        )
                    }
                preparationJob = null
            }
    }

    /**
     * Plan F8a2t: a new live flight pushes the previous one onto [priorFlights] ("Keep last shots"),
     * unless it is the same shot flown again (Replay). Replay and overlay flights are never kept.
     */
    private fun rememberLiveFlight(active: ActiveFlight) {
        if (!browse.value.isLive) return
        val previous = lastLiveFlight
        lastLiveFlight = active
        if (previous == null || previous.trajectory.eventId == active.trajectory.eventId) return
        priorFlights.update { (listOf(previous) + it).take(MAX_PRIOR_FLIGHTS) }
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

    /**
     * Replays session [sessionId] from shot [index], playing; or, with a [shotId] (plan F8d),
     * paused on that shot, looked up in every stored session when [sessionId] doesn't hold it.
     */
    private fun startReplay(
        sessionId: String,
        index: Int,
        shotId: String? = null,
    ) {
        leaveLive()
        browse.update {
            it.copy(
                mode = RangeMode.Replay(sessionId, index),
                loading = true,
                playing = shotId == null,
                shots = emptyList(),
                selectedShotId = null,
                overlayFlights = emptyList(),
                overlayTruncated = false,
                overlayClubs = emptyList(),
                newLiveShot = pendingLiveShot != null,
                show = null,
            )
        }
        flight.value = FlightState(RangePhase.Waiting, displayedShot = null)
        browseJob =
            viewModelScope.launch {
                var session = sessionId
                // Plan F8f: a "View on range" of one shot shows it whoever hit it.
                var stored = replayableShots(sessionId, everyone = shotId != null)
                var start = index
                if (shotId != null) {
                    start = stored.indexOfLaunchShot(shotId)
                    if (start < 0) {
                        findStoredShot(shotId, except = sessionId)?.let { (otherSession, otherShots) ->
                            session = otherSession
                            stored = otherShots
                            start = otherShots.indexOfLaunchShot(shotId)
                        }
                    }
                }
                replayShots = stored
                browse.update {
                    it.copy(
                        mode = RangeMode.Replay(session, index),
                        loading = false,
                        shots = stored.mapIndexed { i, shot -> shot.toRangeShotItem(i + 1) },
                    )
                }
                if (stored.isEmpty()) {
                    browse.update { it.copy(playing = false) }
                } else {
                    showReplayShot(start.coerceIn(0, stored.lastIndex))
                }
            }
    }

    /**
     * Session [sessionId]'s flyable shots, oldest first: the order the session was hit in; only the
     * viewing profile's (plan F8f) unless [everyone].
     */
    private suspend fun replayableShots(
        sessionId: String,
        everyone: Boolean = false,
    ): List<HistoryShot> {
        val profiles = profileFilter
        return history
            .shots(sessionId)
            .first()
            .filter { it.toRangeShotEvent() != null && (everyone || profiles.shows(it.detail.profileId)) }
            .sortedWith(compareBy<HistoryShot>({ it.detail.timestamp }, { it.id }))
    }

    /** The newest stored session (other than [except]) holding [shotId], with its replayable shots. */
    private suspend fun findStoredShot(
        shotId: String,
        except: String,
    ): Pair<String, List<HistoryShot>>? {
        for (candidate in history.sessions(includeImported = true).first()) {
            if (candidate.id == except) continue
            val shots = replayableShots(candidate.id, everyone = true)
            if (shots.indexOfLaunchShot(shotId) >= 0) return candidate.id to shots
        }
        return null
    }

    /** Plan F8d: "View on range" opens paused on the shot; without one it replays from the start. */
    private fun launch(launch: RangeLaunch) {
        modeRequested = true
        startReplay(launch.sessionId, index = 0, shotId = launch.shotId)
    }

    @Suppress("TooGenericExceptionCaught") // Any failure is shown on the range, like Session's message.
    private fun simulateShot() {
        simulateError.value = null
        viewModelScope.launch {
            try {
                piSession.simulateShot()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                simulateError.value = failure.message ?: SIMULATE_FAILED
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

    /**
     * Overlays session [sessionId]'s shots (every session's when `null`), only [club]'s when set,
     * only the newest [limit] when set. Plan F8f: a [show] that follows the current session
     * ([RangeShowSetting.followsCurrentSession]) overlays the current session (or the newest stored
     * one before the first connect) and keeps collecting it, so each new live shot joins the overlay
     * after it flies.
     */
    private fun startOverlay(
        sessionId: String?,
        club: String?,
        limit: Int? = null,
        show: RangeShowSetting? = null,
    ) {
        val follows = show?.followsCurrentSession == true
        val shown = flight.value.displayedShot
        leaveLive()
        browse.update {
            it.copy(
                mode = RangeMode.Overlay(sessionId, club, limit),
                loading = true,
                playing = false,
                shots = emptyList(),
                selectedShotId = null,
                overlayFlights = emptyList(),
                overlayTruncated = false,
                newLiveShot = pendingLiveShot != null && !follows,
                show = show,
            )
        }
        // Following the session keeps the shot on screen (and its metrics); the flight itself stops.
        flight.value = FlightState(RangePhase.Waiting, displayedShot = if (follows) shown else null)
        browseJob =
            viewModelScope.launch {
                if (!follows) {
                    showOverlay(loadOverlayCandidates(sessionId, club), club, limit)
                    return@launch
                }
                val current =
                    history.currentSessionId.value ?: history
                        .sessions()
                        .first()
                        .firstOrNull()
                        ?.id
                browse.update { it.copy(mode = RangeMode.Overlay(current, club, limit)) }
                if (current == null) {
                    showOverlay(emptyList(), club, limit)
                    return@launch
                }
                history.shots(current).collect { stored ->
                    val profiles = profileFilter
                    showOverlay(
                        stored.filter { it.toRangeShotEvent() != null && profiles.shows(it.detail.profileId) },
                        club,
                        limit,
                    )
                }
            }
    }

    /** Builds the overlay from [candidates] (newest first): [club]'s, capped at [limit] or the overlay cap. */
    private suspend fun showOverlay(
        candidates: List<HistoryShot>,
        club: String?,
        limit: Int?,
    ) {
        val clubs = orderClubs(candidates.mapNotNull { shot -> shot.detail.club?.takeIf { it.isNotEmpty() } })
        val matching = if (club == null) candidates else candidates.filter { it.detail.club == club }
        val capped = matching.take(limit ?: RangeBrowseState.OVERLAY_CAP)
        val missing = capped.filter { it.id !in overlayCache }
        if (missing.isNotEmpty()) {
            val air = conditions.conditions.value
            val bearing = conditions.targetBearing.value
            val computed =
                withContext(computeDispatcher) {
                    missing.associate { it.id to overlayTrajectory(it, air, bearing) }
                }
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
                overlayTruncated = limit == null && matching.size > RangeBrowseState.OVERLAY_CAP,
                overlayClubs = clubs,
            )
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
        // Plan F8f: only the viewing profile's shots.
        val profiles = profileFilter
        val flyable: (HistoryShot) -> Boolean = {
            it.toRangeShotEvent() != null && profiles.shows(it.detail.profileId)
        }
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

    /**
     * Runs on [computeDispatcher]: touches no view-model state. Plan F2b: planned like a live
     * flight (from the stored Pi detail, so each landing matches its carry), then downsampled.
     */
    private fun overlayTrajectory(
        stored: HistoryShot,
        air: Conditions,
        bearing: TargetBearing?,
    ): FlightTrajectory? {
        val shot = stored.toRangeShotEvent() ?: return null
        val measurements = (stored.detail.toFlightMeasurements() ?: shot.toFlightMeasurements()).copy(id = shot.eventId)
        return flightPlan(measurements, air, bearing)
            ?.trajectory
            ?.downsampled(RangeBrowseState.OVERLAY_TRAJECTORY_POINTS)
    }

    private fun setOverlayClub(club: String?) {
        val mode = browse.value.mode as? RangeMode.Overlay ?: return
        if (mode.club != club) startOverlay(mode.sessionId, club, mode.limit, browse.value.show)
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
        browse.update { RangeBrowseState(sessions = it.sessions, speed = it.speed, profiles = it.profiles) }
        val live = pendingLiveShot
        pendingLiveShot = null
        if (live != null) {
            prepare(live)
        } else {
            flight.value = FlightState(RangePhase.Waiting, latestShownShot())
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

    /**
     * The stored shot's Pi detail when it has one (its spin-adjusted carry anchors the estimate),
     * keyed by the event id the shot flies under.
     */
    private fun measurementsFor(shot: ShotEvent): FlightMeasurements =
        (historyDetails[shot.eventId]?.toFlightMeasurements() ?: shot.toFlightMeasurements()).copy(id = shot.eventId)

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

    private data class SimulateState(
        val available: Boolean,
        val error: String?,
    )

    companion object {
        /** How long the landed ball stays on screen before the next shot (DrivingRangeViewModel.swift). */
        const val LANDING_DWELL_MILLIS = 1_250L
        const val CLUB_CHANGE_FAILED = "Couldn't change the club."
        const val SIMULATE_FAILED = "Couldn't simulate a shot."
        const val FLIGHT_UNAVAILABLE = "This shot can't be flown."

        /** Plan F8a2t: the most earlier live flights kept for "Keep last shots". */
        const val MAX_PRIOR_FLIGHTS = ShotTrail.PRIOR_LAYERS
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Plan F8f: hidden while "Show total + roll (est.)" is off; its label in the chosen units. */
private fun RangeRollOut.shownWith(numbers: RangeNumbers): RangeRollOut? =
    when {
        !numbers.showTotal -> null
        units == numbers.units -> this
        else -> copy(units = numbers.units)
    }

private fun ShotDistanceEstimate.toRollOut(): RangeRollOut =
    RangeRollOut(
        carryYards = carryYards,
        rollYards = rollYards,
        totalYards = totalYards,
        carryEstimated = isAdjusted,
    )
