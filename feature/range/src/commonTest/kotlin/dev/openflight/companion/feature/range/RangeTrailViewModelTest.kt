// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Plan F8a2t: the range VM's shot trail state (the settings, and the earlier live flights to keep). */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeTrailViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository(settings)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            history = FakeShotHistoryRepository(),
            conditions = FakeConditionsRepository(),
            piSession = FakePiSessionRepository(),
            flightPlan = ::cheapFlightPlan,
            computeDispatcher = StandardTestDispatcher(scheduler),
        )

    @Test
    fun theTrailFollowsTheSettings() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                assertThat(awaitItem().camera.trail).isEqualTo(RangeTrailState())
                settings.setShotTrail(ShotTrailStyle.SPIN_RIBBON)
                settings.setLandingEffect(LandingEffect.BURST)
                settings.setShotTrailKeepLast(3)
                val trail = awaitUntil { it.camera.trail.keepLast == 3 }.camera.trail
                assertThat(trail.style).isEqualTo(ShotTrailStyle.SPIN_RIBBON)
                assertThat(trail.landingEffect).isEqualTo(LandingEffect.BURST)
            }
        }

    @Test
    fun aLiveFlightCarriesItsSpinAndClubColour() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot(club = "pw", spinRpm = 8_900.0))
                val flight = awaitUntil { it.activeFlight != null }.activeFlight
                assertThat(flight).isNotNull()
                assertThat(flight!!.spinRpm).isEqualTo(8_900.0)
                assertThat(flight.clubColorIndex).isEqualTo(GolfClub.PITCHING_WEDGE.ordinal)
            }
        }

    @Test
    fun keepLastKeepsTheNewestEarlierLiveFlightsUpToItsCount() =
        runTest(scheduler) {
            settings.shotTrailKeepLast.value = 3
            val viewModel = makeViewModel()
            val flown = List(5) { makeDrivingRangeShot() }

            viewModel.uiState.test {
                for (shot in flown) fly(viewModel, shot)
                // The current flight is the fifth; the three before it, newest first.
                val priors = expectMostRecentItem().camera.trail.priorFlights
                assertThat(priors.map { it.trajectory.eventId }).containsExactly(
                    flown[3].eventId,
                    flown[2].eventId,
                    flown[1].eventId,
                )

                settings.setShotTrailKeepLast(0)
                assertThat(awaitUntil { it.camera.trail.keepLast == 0 }.camera.trail.priorFlights).isEmpty()
            }
        }

    @Test
    fun replayingTheShownShotDoesNotKeepItTwice() =
        runTest(scheduler) {
            settings.shotTrailKeepLast.value = 3
            val viewModel = makeViewModel()
            val first = makeDrivingRangeShot()
            val second = makeDrivingRangeShot()

            viewModel.uiState.test {
                fly(viewModel, first)
                fly(viewModel, second)
                viewModel.onEvent(DrivingRangeEvent.Replay)
                awaitUntil { it.phase == RangePhase.Flying }
                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS + 1)

                val priors = expectMostRecentItem().camera.trail.priorFlights
                assertThat(priors.map { it.trajectory.eventId }).containsExactly(first.eventId)
            }
        }

    /** Flies [shot] live to its landing and past the dwell. */
    private suspend fun ReceiveTurbine<DrivingRangeUiState>.fly(
        viewModel: DrivingRangeViewModel,
        shot: dev.openflight.companion.core.model.ShotEvent,
    ) {
        shots.emit(shot)
        awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == shot.eventId }
        viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
        scheduler.advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS + 1)
        scheduler.runCurrent()
    }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }
}
