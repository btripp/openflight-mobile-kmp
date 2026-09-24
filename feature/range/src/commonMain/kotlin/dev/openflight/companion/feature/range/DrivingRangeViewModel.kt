// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInput
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 *
 * The simulation runs on [computeDispatcher] (`Dispatchers.Default`); tests inject a test
 * dispatcher so the dwell and the simulation run on virtual time.
 */
class DrivingRangeViewModel(
    private val shots: ShotRepository,
    settings: SettingsRepository,
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val simulation: (FlightInput) -> FlightTrajectory = BallFlightSimulator()::simulate,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val initialShot = shots.latestShot.value
    private val flight = MutableStateFlow(FlightState(phase = RangePhase.Waiting, displayedShot = initialShot))
    private val clubRequest = MutableStateFlow(ClubRequest())

    private var lastObservedEventId: String? = initialShot?.eventId
    private var pendingShot: ShotEvent? = null
    private var preparationJob: Job? = null
    private var landingJob: Job? = null

    /** Bumped by every preparation and by [suspend], so a stale simulation never lands. */
    private var generation = 0L

    val uiState: StateFlow<DrivingRangeUiState> =
        combine(
            flight,
            settings.selectedClub,
            shots.connectionState,
            clubRequest,
        ) { flight, club, connection, request ->
            val clubState =
                RangeClubState(
                    selected = club,
                    selectionEnabled = connection == ConnectionState.Connected,
                    isChanging = request.inFlight,
                    error = request.error,
                )
            val shot = flight.displayedShot
            if (shot == null) {
                DrivingRangeUiState.Ready(clubState)
            } else {
                DrivingRangeUiState.Showing(shot, flight.phase, flight.activeFlight, clubState)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = initialState(),
        )

    init {
        viewModelScope.launch { shots.latestShot.collect(::observe) }
    }

    fun onEvent(event: DrivingRangeEvent) {
        when (event) {
            DrivingRangeEvent.Replay -> replayDisplayedShot()
            DrivingRangeEvent.FlightCompleted -> animationCompleted()
            is DrivingRangeEvent.ClubSelected -> changeClub(event.club)
        }
    }

    /** Cancels any preparation, flight and dwell, drops the queued shot and returns to waiting. */
    fun suspend() {
        generation++
        preparationJob?.cancel()
        landingJob?.cancel()
        preparationJob = null
        landingJob = null
        pendingShot = null
        flight.value = flight.value.copy(phase = RangePhase.Waiting, activeFlight = null)
    }

    private fun observe(shot: ShotEvent?) {
        if (shot == null || shot.eventId == lastObservedEventId) return
        lastObservedEventId = shot.eventId
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
        landingJob =
            viewModelScope.launch {
                delay(LANDING_DWELL_MILLIS)
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
        preparationJob =
            viewModelScope.launch {
                val trajectory = withContext(computeDispatcher) { simulation(input) }
                if (generation != current) return@launch
                flight.value =
                    flight.value.copy(phase = RangePhase.Flying, activeFlight = ActiveFlight(trajectory, current))
                preparationJob = null
            }
    }

    private fun advanceAfterLanding() {
        landingJob = null
        flight.value = flight.value.copy(activeFlight = null)
        val next = pendingShot
        if (next != null) {
            pendingShot = null
            prepare(next)
        } else {
            flight.value = flight.value.copy(phase = RangePhase.Waiting)
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

    private data class FlightState(
        val phase: RangePhase,
        val displayedShot: ShotEvent?,
        val activeFlight: ActiveFlight? = null,
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
