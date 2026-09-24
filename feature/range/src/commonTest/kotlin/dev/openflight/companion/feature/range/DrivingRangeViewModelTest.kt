// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Ported from ios/OpenFlightTests/DrivingRangeViewModelTests.swift (the first six tests keep the
 * Swift names), plus the timing the Swift tests stub out (`sleep: { _ in }`): here the dwell and
 * the simulation run on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingRangeViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        shots: FakeShotRepository,
        computeDispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
    ): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            simulation = ::makeTestTrajectory,
            computeDispatcher = computeDispatcher,
        )

    // region Ported from DrivingRangeViewModelTests.swift

    @Test
    fun existingShotIsDisplayedWithoutAutomaticReplay() =
        runTest(scheduler) {
            val shot = makeDrivingRangeShot()
            val viewModel = makeViewModel(FakeShotRepository(settings, currentShot = shot))

            viewModel.uiState.test {
                advanceUntilIdle()
                val state = expectMostRecentItem()
                assertThat(state.displayedShot).isEqualTo(shot)
                assertThat(state.phase).isEqualTo(RangePhase.Waiting)
                assertThat(state.activeFlight).isNull()
            }
        }

    @Test
    fun newShotPreparesAndStartsFlight() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)
            val shot = makeDrivingRangeShot()

            viewModel.uiState.test {
                shots.emit(shot)
                val state = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(state.displayedShot).isEqualTo(shot)
                assertThat(state.activeFlight?.trajectory?.eventId).isEqualTo(shot.eventId)
            }
        }

    @Test
    fun onlyNewestPendingShotPlaysAfterCurrentFlight() =
        runTest(scheduler) {
            val first = makeDrivingRangeShot()
            val second = makeDrivingRangeShot(ballSpeedMph = 140.0)
            val newest = makeDrivingRangeShot(ballSpeedMph = 160.0)
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(first)
                awaitUntil { it.phase == RangePhase.Flying }
                shots.emit(second)
                runCurrent()
                shots.emit(newest)
                runCurrent()
                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                val state =
                    awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == newest.eventId }

                assertThat(state.displayedShot).isEqualTo(newest)
                assertThat(state.displayedShot).isNotEqualTo(second)
            }
        }

    @Test
    fun replayStartsDisplayedShotAgain() =
        runTest(scheduler) {
            val shot = makeDrivingRangeShot()
            val viewModel = makeViewModel(FakeShotRepository(settings, currentShot = shot))

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Replay)
                val state = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(state.activeFlight?.trajectory?.eventId).isEqualTo(shot.eventId)
            }
        }

    @Test
    fun invalidShotSurfacesUnavailableState() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot(ballSpeedMph = 0.0))
                val state = awaitUntil { it.phase is RangePhase.Unavailable }
                assertThat(state.phase).isEqualTo(RangePhase.Unavailable("Ball speed is unavailable for this shot."))
                assertThat(state.activeFlight).isNull()
            }
        }

    @Test
    fun suspendCancelsAndReturnsToWaiting() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                awaitUntil { it.phase == RangePhase.Flying }

                viewModel.suspend()

                val state = awaitUntil { it.phase == RangePhase.Waiting }
                assertThat(state.activeFlight).isNull()
            }
        }

    // endregion

    @Test
    fun theSimulationRunsOnTheComputeDispatcherWhilePreparing() =
        runTest(scheduler) {
            val compute = TestCoroutineScheduler()
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots, computeDispatcher = StandardTestDispatcher(compute))

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                advanceUntilIdle()
                val preparing = expectMostRecentItem()
                assertThat(preparing.phase).isEqualTo(RangePhase.Preparing)
                assertThat(preparing.activeFlight).isNull()

                compute.advanceUntilIdle()
                assertThat(awaitUntil { it.phase == RangePhase.Flying }.activeFlight).isNotNull()
            }
        }

    @Test
    fun theLandingDwellsForOnePointTwoFiveSecondsBeforeWaiting() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                awaitUntil { it.phase == RangePhase.Flying }

                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                runCurrent()
                assertThat(expectMostRecentItem().phase).isEqualTo(RangePhase.Landed)

                assertThat(DrivingRangeViewModel.LANDING_DWELL_MILLIS).isEqualTo(1_250L)
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS - 1)
                runCurrent()
                expectNoEvents()

                advanceTimeBy(1)
                runCurrent()
                val waiting = expectMostRecentItem()
                assertThat(waiting.phase).isEqualTo(RangePhase.Waiting)
                assertThat(waiting.activeFlight).isNull()
            }
        }

    @Test
    fun aShotDuringTheDwellWaitsForItThenFlies() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)
            val next = makeDrivingRangeShot(ballSpeedMph = 120.0)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                awaitUntil { it.phase == RangePhase.Flying }
                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                runCurrent()
                shots.emit(next)
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS - 1)
                runCurrent()
                assertThat(expectMostRecentItem().phase).isEqualTo(RangePhase.Landed)

                val state = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(state.displayedShot).isEqualTo(next)
            }
        }

    @Test
    fun replayIsIgnoredWhileFlyingAndGetsANewPlaybackIdAfterLanding() =
        runTest(scheduler) {
            val shot = makeDrivingRangeShot()
            val viewModel = makeViewModel(FakeShotRepository(settings, currentShot = shot))

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Replay)
                val firstFlight = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(firstFlight.canReplay).isFalse()
                viewModel.onEvent(DrivingRangeEvent.Replay)
                runCurrent()
                expectNoEvents()

                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                awaitUntil { it.phase == RangePhase.Waiting }
                viewModel.onEvent(DrivingRangeEvent.Replay)
                val secondFlight = awaitUntil { it.phase == RangePhase.Flying }

                assertThat(secondFlight.activeFlight?.playbackId)
                    .isNotEqualTo(firstFlight.activeFlight?.playbackId)
            }
        }

    @Test
    fun suspendWhilePreparingDropsTheStaleSimulationAndTheQueuedShot() =
        runTest(scheduler) {
            val compute = TestCoroutineScheduler()
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots, computeDispatcher = StandardTestDispatcher(compute))

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                advanceUntilIdle()
                shots.emit(makeDrivingRangeShot(ballSpeedMph = 130.0))
                advanceUntilIdle()
                viewModel.suspend()
                compute.advanceUntilIdle()
                advanceUntilIdle()

                val state = expectMostRecentItem()
                assertThat(state.phase).isEqualTo(RangePhase.Waiting)
                assertThat(state.activeFlight).isNull()
            }
        }

    @Test
    fun missingLaunchAndSpinFlyAnEstimatedFlight() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot(launchAngle = null, spinRpm = null))
                assertThat(awaitUntil { it.phase == RangePhase.Flying }.usesEstimatedFlight).isTrue()
            }
        }

    @Test
    fun aNewShotAfterAnUnavailableOneFlies() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot(carryYards = 0.0))
                awaitUntil { it.phase == RangePhase.Unavailable("Carry distance is unavailable for this shot.") }
                val good = makeDrivingRangeShot()
                shots.emit(good)
                assertThat(awaitUntil { it.phase == RangePhase.Flying }.displayedShot).isEqualTo(good)
            }
        }

    @Test
    fun theClubSelectorIsEnabledOnlyWhileConnectedAndShowsFailures() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            shots.connectionState.value = ConnectionState.Scanning
            shots.setClubResponse = { error("Pi said no") }
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                assertThat(awaitUntil { it.club.selected == GolfClub.DRIVER }.club.selectionEnabled).isFalse()
                shots.connectionState.value = ConnectionState.Connected
                assertThat(awaitUntil { it.club.selectionEnabled }).isInstanceOf<DrivingRangeUiState.Ready>()

                viewModel.onEvent(DrivingRangeEvent.ClubSelected(GolfClub.IRON_7))
                val failed = awaitUntil { it.club.error != null }
                assertThat(failed.club.error).isEqualTo("Pi said no")
                assertThat(failed.club.isChanging).isFalse()
                assertThat(failed.club.selected).isEqualTo(GolfClub.DRIVER)
            }
        }

    @Test
    fun aConfirmedClubChangeShowsTheNewClub() =
        runTest(scheduler) {
            val viewModel = makeViewModel(FakeShotRepository(settings))

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.ClubSelected(GolfClub.IRON_7))
                val state = awaitUntil { it.club.selected == GolfClub.IRON_7 }
                assertThat(state.club.error).isNull()
            }
        }

    /** Awaits states (virtual time advances meanwhile) until one matches, like the Swift `waitUntil`. */
    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }
}
