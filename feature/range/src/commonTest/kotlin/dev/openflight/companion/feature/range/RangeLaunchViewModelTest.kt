// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.FlightInput
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Plan F8d: "View on range" launches and simulating a shot on a `--mock` Pi. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeLaunchViewModelTest {
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

    private fun makeViewModel(shots: FakeShotRepository = FakeShotRepository(settings)): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            history = history,
            conditions = FakeConditionsRepository(),
            piSession = piSession,
            simulation = ::countingSimulation,
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

    // region Launch positioning

    @Test
    fun launchOnAShotOpensPausedOnItAndFliesItOnce() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", shotId = "2")))
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(flying.mode).isEqualTo(RangeMode.Replay("s1", 1))
                assertThat(flying.displayedShot?.eventId).isEqualTo(eventIdFor(2))
                assertThat(flying.browse.playing).isFalse()
                assertThat(flying.browse.selectedShotId).isEqualTo("2")

                viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
                advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS + 1)
                val landed = awaitUntil { it.phase == RangePhase.Waiting }
                // Paused: it stays on the launched shot instead of advancing.
                assertThat(landed.mode).isEqualTo(RangeMode.Replay("s1", 1))
                assertThat(landed.displayedShot?.eventId).isEqualTo(eventIdFor(2))
                assertThat(simulated).containsExactly(eventIdFor(2))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun afterALaunchNextPreviousAndPlayWork() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", shotId = "2")))
                awaitUntil { it.phase == RangePhase.Flying }

                viewModel.onEvent(DrivingRangeEvent.NextShot)
                assertThat(awaitUntil { it.displayedShot?.eventId == eventIdFor(3) }.mode)
                    .isEqualTo(RangeMode.Replay("s1", 2))
                viewModel.onEvent(DrivingRangeEvent.PreviousShot)
                viewModel.onEvent(DrivingRangeEvent.PreviousShot)
                assertThat(awaitUntil { it.displayedShot?.eventId == eventIdFor(1) }.mode)
                    .isEqualTo(RangeMode.Replay("s1", 0))

                viewModel.onEvent(DrivingRangeEvent.PlayPause)
                assertThat(awaitUntil { it.browse.playing }.browse.playing).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun launchWithoutAShotReplaysTheSessionFromTheStart() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1")))
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(flying.mode).isEqualTo(RangeMode.Replay("s1", 0))
                assertThat(flying.browse.playing).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun launchFindsTheShotByEventIdTimestampOrTheIdARowWithoutOneFliesUnder() =
        runTest(scheduler) {
            history.put(
                "s1",
                listOf(
                    storedShot(3, eventId = null),
                    storedShot(2, timestamp = "2026-09-25T10:00:02.123456"),
                    storedShot(1),
                ),
            )
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                // A live Session row's id is the SSE/BLE event id, in any case.
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", eventIdFor(1).lowercase())))
                assertThat(awaitUntil { it.phase == RangePhase.Flying }.mode).isEqualTo(RangeMode.Replay("s1", 0))

                // A Pi-sourced row's id is the Pi's timestamp.
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", "2026-09-25T10:00:02.123456")))
                assertThat(awaitUntil { it.mode == RangeMode.Replay("s1", 1) && it.phase == RangePhase.Flying }.mode)
                    .isEqualTo(RangeMode.Replay("s1", 1))

                // A row stored without an event id flies under historyEventId(rowId) (plan F8a1).
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", historyEventId(3))))
                val third = awaitUntil { it.mode == RangeMode.Replay("s1", 2) && it.phase == RangePhase.Flying }
                assertThat(third.displayedShot?.eventId).isEqualTo(historyEventId(3))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aShotNotInTheGivenSessionIsFoundInItsOwnSession() =
        runTest(scheduler) {
            seedSession()
            history.put("s2", listOf(storedShot(11, sessionId = "s2"), storedShot(10, sessionId = "s2")))
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                // A Pi session outlives the phone's history session: its older shots are filed elsewhere.
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s2", shotId = eventIdFor(2))))
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(flying.mode).isEqualTo(RangeMode.Replay("s1", 1))
                assertThat(flying.displayedShot?.eventId).isEqualTo(eventIdFor(2))
                assertThat(flying.browse.shots.map { it.id }).containsExactly("1", "2", "3")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun anUnknownShotOpensTheSessionPausedOnItsFirstShot() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", shotId = "missing")))
                val flying = awaitUntil { it.phase == RangePhase.Flying }
                assertThat(flying.mode).isEqualTo(RangeMode.Replay("s1", 0))
                assertThat(flying.browse.playing).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLiveShotDuringALaunchedReplayOffersTheWayBack() =
        runTest(scheduler) {
            seedSession()
            val shots = FakeShotRepository(settings)
            val viewModel = makeViewModel(shots)

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("s1", shotId = "2")))
                awaitUntil { it.phase == RangePhase.Flying }
                val live = makeDrivingRangeShot()
                shots.emit(live)
                val chip = awaitUntil { it.browse.newLiveShot }
                assertThat(chip.mode).isInstanceOf(RangeMode.Replay::class)

                viewModel.onEvent(DrivingRangeEvent.ReturnToLive)
                assertThat(awaitUntil { it.phase == RangePhase.Flying && it.displayedShot == live }.mode)
                    .isEqualTo(RangeMode.Live)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion

    // region Simulate

    @Test
    fun canSimulateOnlyOnAConnectedMockPi() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                // Connected, mock mode not reported yet.
                assertThat(awaitUntil { !it.canSimulate }.canSimulate).isFalse()
                piSession.mockMode.value = true
                awaitUntil { it.canSimulate }

                // A real Pi.
                piSession.mockMode.value = false
                awaitUntil { !it.canSimulate }
                piSession.mockMode.value = true
                awaitUntil { it.canSimulate }

                // Over Bluetooth (no Socket.IO link).
                piSession.linkState.value = PiLinkState.WifiOnly
                awaitUntil { !it.canSimulate }
                piSession.linkState.value = PiLinkState.Connected
                awaitUntil { it.canSimulate }

                // Disconnected.
                piSession.linkState.value = PiLinkState.Idle
                awaitUntil { !it.canSimulate }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun simulateShotAsksThePiAndFliesNothingItself() =
        runTest(scheduler) {
            piSession.mockMode.value = true
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitUntil { it.canSimulate }
                viewModel.onEvent(DrivingRangeEvent.SimulateShot)
                runCurrent()
                assertThat(piSession.commands).containsExactly("simulate_shot")
                assertThat(simulated).containsExactly()
                assertThat(expectMostRecentItemOrNull()?.simulateError).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aFailedSimulateIsShownUntilTheNextTry() =
        runTest(scheduler) {
            piSession.mockMode.value = true
            piSession.linkState.value = PiLinkState.Idle
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                viewModel.onEvent(DrivingRangeEvent.SimulateShot)
                val failed = awaitUntil { it.simulateError != null }
                assertThat(failed.simulateError).isNotNull()

                piSession.linkState.value = PiLinkState.Connected
                viewModel.onEvent(DrivingRangeEvent.SimulateShot)
                awaitUntil { it.simulateError == null }
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion

    @Test
    fun indexOfLaunchShotPrefersTheRowIdThenTheEventIdThenTheTimestamp() {
        val shots =
            listOf(
                storedShot(1, timestamp = "2"),
                storedShot(2),
                storedShot(3, eventId = null),
            )

        assertThat(shots.indexOfLaunchShot("2")).isEqualTo(1)
        assertThat(shots.indexOfLaunchShot(eventIdFor(1))).isEqualTo(0)
        assertThat(shots.indexOfLaunchShot(historyEventId(3))).isEqualTo(2)
        assertThat(shots.indexOfLaunchShot("2026-09-25T10:00:02")).isEqualTo(1)
        assertThat(shots.indexOfLaunchShot("nope")).isEqualTo(-1)
    }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private fun ReceiveTurbine<DrivingRangeUiState>.expectMostRecentItemOrNull(): DrivingRangeUiState? =
        try {
            expectMostRecentItem()
        } catch (_: AssertionError) {
            null
        }
}
