// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.FlightInput
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
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

/** Plan F8a1: replay, overlay and the view transform on top of the unchanged live range. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeReplayViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()
    private val history = FakeShotHistoryRepository()
    private val piSession = FakePiSessionRepository()
    private val simulated = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        shots: FakeShotRepository = FakeShotRepository(settings),
        cheap: Boolean = false,
    ): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            history = history,
            conditions = FakeConditionsRepository(),
            piSession = piSession,
            flightPlan =
                if (cheap) {
                    ::cheapFlightPlan
                } else {
                    { m, c, b -> testFlightPlan(m, c, b, ::countingSimulation) }
                },
            computeDispatcher = StandardTestDispatcher(scheduler),
            distanceEstimate = { _, _, _ -> null },
        )

    private fun countingSimulation(input: FlightInput): FlightTrajectory {
        simulated += input.eventId
        return makeTestTrajectory(input)
    }

    /** Session "s1": three shots, stored newest first like the repository returns them. */
    private fun seedSession() {
        history.put("s1", listOf(storedShot(3), storedShot(2, club = "7-iron"), storedShot(1)))
    }

    @Test
    fun replayFliesTheSessionOldestFirstAndAdvancesAfterEachLanding() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartReplay("s1"))
                val first = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(first.mode).isEqualTo(RangeMode.Replay("s1", 0))
                assertThat(first.displayedShot?.eventId).isEqualTo(eventIdFor(1))
                assertThat(first.browse.shots.map { it.id }).containsExactly("1", "2", "3")
                assertThat(first.browse.playing).isTrue()

                val order = mutableListOf(first.displayedShot!!.eventId)
                repeat(2) {
                    viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                    advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS)
                    order +=
                        awaitUntil { it.phase == RangePhase.Flying && it.displayedShot!!.eventId !in order }
                            .displayedShot!!
                            .eventId
                }
                assertThat(order).containsExactly(eventIdFor(1), eventIdFor(2), eventIdFor(3))

                // After the last shot, playback stops and waits.
                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                val done = awaitUntil { it.phase == RangePhase.Waiting }
                assertThat(done.browse.playing).isFalse()
                assertThat(done.mode).isEqualTo(RangeMode.Replay("s1", 2))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun pausedReplayStaysOnItsShotAndNextAndPreviousStep() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartReplay("s1"))
                awaitUntil { it.phase == RangePhase.Flying }
                viewModel.onEvent(DrivingRangeEvent.PlayPause)
                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS + 1)
                val paused = awaitUntil { it.phase == RangePhase.Waiting }
                assertThat(paused.mode).isEqualTo(RangeMode.Replay("s1", 0))

                viewModel.onEvent(DrivingRangeEvent.NextShot)
                assertThat(awaitUntil { it.phase == RangePhase.Flying }.displayedShot?.eventId).isEqualTo(eventIdFor(2))
                viewModel.onEvent(DrivingRangeEvent.PreviousShot)
                val back = awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == eventIdFor(1) }
                assertThat(back.mode).isEqualTo(RangeMode.Replay("s1", 0))
                // Previous at the first shot stays put.
                viewModel.onEvent(DrivingRangeEvent.PreviousShot)
                runCurrent()
                assertThat(expectMostRecentItemOr(back).mode).isEqualTo(RangeMode.Replay("s1", 0))

                viewModel.onEvent(DrivingRangeEvent.SelectShot("3"))
                assertThat(awaitUntil { it.displayedShot?.eventId == eventIdFor(3) }.mode)
                    .isEqualTo(RangeMode.Replay("s1", 2))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun doubleSpeedHalvesTheDwellAndFliesFaster() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.SetSpeed(ReplaySpeed.DOUBLE))
                viewModel.onEvent(DrivingRangeEvent.StartReplay("s1"))
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(flying.activeFlight?.speed).isEqualTo(2.0)

                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                runCurrent()
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS / 2 + 1)
                assertThat(awaitUntil { it.displayedShot?.eventId == eventIdFor(2) }.mode)
                    .isEqualTo(RangeMode.Replay("s1", 1))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLiveShotDuringReplayShowsTheChipAndReturningToLiveFliesIt() =
        runTest(scheduler) {
            seedSession()
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)
            val live = makeDrivingRangeShot()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartReplay("s1"))
                awaitUntil { it.phase == RangePhase.Flying }
                shots.emit(live)
                val chip = awaitUntil { it.browse.newLiveShot }
                assertThat(chip.mode).isInstanceOf(RangeMode.Replay::class)
                assertThat(chip.displayedShot?.eventId).isEqualTo(eventIdFor(1))

                viewModel.onEvent(DrivingRangeEvent.ReturnToLive)
                val back = awaitUntil { it.phase == RangePhase.Flying && it.displayedShot == live }
                assertThat(back.mode).isEqualTo(RangeMode.Live)
                assertThat(back.browse.newLiveShot).isFalse()
                assertThat(back.activeFlight?.speed).isEqualTo(1.0)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun returningToLiveWithoutANewShotShowsTheLatestWithoutFlying() =
        runTest(scheduler) {
            seedSession()
            val current = makeDrivingRangeShot()
            val viewModel = makeViewModel(FakeShotRepository(settings, currentShot = current))

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartOverlay(sessionId = "s1"))
                awaitUntil { it.browse.overlayFlights.isNotEmpty() }
                viewModel.onEvent(DrivingRangeEvent.ReturnToLive)
                val live = awaitUntil { it.mode == RangeMode.Live }
                assertThat(live.displayedShot).isEqualTo(current)
                assertThat(live.phase).isEqualTo(RangePhase.Waiting)
                assertThat(live.activeFlight).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theOverlayIsCappedAtTwoHundredNewestFirst() =
        runTest(scheduler) {
            history.put(
                "big",
                (250L downTo 1L).map {
                    storedShot(
                        it,
                        timestamp =
                            "2026-09-25T10:" + it.toString().padStart(5, '0'),
                    )
                },
            )
            // 250 stored shots: no physics here (see cheapFlightPlan), only the cap and the order.
            val viewModel = makeViewModel(cheap = true)

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartOverlay(sessionId = "big"))
                val overlay = awaitUntil { it.browse.overlayFlights.isNotEmpty() }
                assertThat(overlay.browse.overlayFlights).hasSize(RangeBrowseState.OVERLAY_CAP)
                assertThat(
                    overlay.browse.overlayFlights
                        .first()
                        .shotId,
                ).isEqualTo("250")
                assertThat(
                    overlay.browse.overlayFlights
                        .last()
                        .shotId,
                ).isEqualTo("51")
                assertThat(overlay.browse.overlayTruncated).isTrue()
                assertThat(overlay.browse.overlayFlights.map { it.trajectory.points.size })
                    .each { it.isGreaterThan(1) }
                // Static trajectories: nothing flies.
                assertThat(overlay.activeFlight).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun overlayTrajectoriesAreSimulatedOncePerShot() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartOverlay(sessionId = null))
                val all = awaitUntil { it.browse.overlayFlights.size == 3 }
                assertThat(all.browse.overlayClubs).containsExactly("driver", "7-iron")

                viewModel.onEvent(DrivingRangeEvent.SetOverlayClub("7-iron"))
                val irons = awaitUntil { it.browse.overlayFlights.size == 1 && !it.browse.loading }
                assertThat(irons.mode).isEqualTo(RangeMode.Overlay(null, "7-iron"))
                viewModel.onEvent(DrivingRangeEvent.SetOverlayClub(null))
                awaitUntil { it.browse.overlayFlights.size == 3 && !it.browse.loading }

                assertThat(simulated.sorted()).containsExactly(eventIdFor(1), eventIdFor(2), eventIdFor(3))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun selectingAnOverlayShotHighlightsItAndShowsItsMetrics() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartOverlay(sessionId = "s1"))
                awaitUntil { it.browse.overlayFlights.isNotEmpty() }
                viewModel.onEvent(DrivingRangeEvent.SelectShot("2"))
                val selected = awaitUntil { it.browse.selectedShotId == "2" && it.displayedShot != null }
                assertThat(selected.displayedShot?.club).isEqualTo("7-iron")
                assertThat(selected.phase).isEqualTo(RangePhase.Waiting)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun overlayNextAndPreviousStepThroughTheListNewestFirst() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.StartOverlay(sessionId = "s1"))
                val loaded = awaitUntil { it.browse.overlayFlights.isNotEmpty() }
                assertThat(loaded.browse.selectedIndex).isEqualTo(-1)
                assertThat(loaded.browse.canSelectPreviousShot).isFalse()
                assertThat(loaded.browse.canSelectNextShot).isTrue()

                // From no selection, Next picks #1: the newest shot.
                viewModel.onEvent(DrivingRangeEvent.NextShot)
                awaitUntil { it.browse.selectedShotId == "3" && it.displayedShot != null }
                viewModel.onEvent(DrivingRangeEvent.NextShot)
                val second = awaitUntil { it.browse.selectedShotId == "2" && it.displayedShot?.club == "7-iron" }
                assertThat(second.browse.canSelectPreviousShot).isTrue()
                viewModel.onEvent(DrivingRangeEvent.PreviousShot)
                awaitUntil { it.browse.selectedShotId == "3" }
                viewModel.onEvent(DrivingRangeEvent.NextShot)
                viewModel.onEvent(DrivingRangeEvent.NextShot)
                val last = awaitUntil { it.browse.selectedShotId == "1" }
                assertThat(last.browse.canSelectNextShot).isFalse()
                assertThat(last.phase).isEqualTo(RangePhase.Waiting)

                // Past the end nothing changes.
                viewModel.onEvent(DrivingRangeEvent.NextShot)
                advanceUntilIdle()
                assertThat(expectMostRecentItemOr(last).browse.selectedShotId).isEqualTo("1")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theViewTransformIsKeptAndResetRestoresTheFollowCamera() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                val zoomed = ViewTransform().zoomedBy(2.0)
                viewModel.onEvent(DrivingRangeEvent.ViewChanged(zoomed))
                val transformed = awaitUntil { it.browse.view == zoomed }
                assertThat(transformed.browse.userTransformed).isTrue()

                viewModel.onEvent(DrivingRangeEvent.ResetView)
                assertThat(awaitUntil { it.browse.view.isIdentity }.browse.userTransformed).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun thePickerListsTheStoredSessions() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                val state = awaitUntil { it.browse.sessions.isNotEmpty() }
                assertThat(state.browse.sessions.map { it.id }).containsExactly("s1")
                assertThat(
                    state.browse.sessions
                        .single()
                        .shotCount,
                ).isEqualTo(3)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aFlightCarriesItsEstimatedRollOut() =
        runTest(scheduler) {
            val shots = FakeShotRepository(settings)
            val viewModel =
                DrivingRangeViewModel(
                    shots = shots,
                    settings = settings,
                    history = history,
                    conditions = FakeConditionsRepository(),
                    piSession = piSession,
                    flightPlan = { m, c, b -> testFlightPlan(m, c, b) },
                    computeDispatcher = StandardTestDispatcher(scheduler),
                )

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                val rollOut = flying.rollOut
                assertThat(rollOut).isNotNull()
                assertThat(rollOut!!.totalYards).isEqualTo(rollOut.carryYards + rollOut.rollYards)
                assertThat(rollOut.rollYards).isGreaterThan(0.0)
                // ISA and calm: the carry is the server's own number.
                assertThat(rollOut.carryEstimated).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private fun ReceiveTurbine<DrivingRangeUiState>.expectMostRecentItemOr(
        fallback: DrivingRangeUiState,
    ): DrivingRangeUiState =
        try {
            expectMostRecentItem()
        } catch (_: AssertionError) {
            fallback
        }
}
