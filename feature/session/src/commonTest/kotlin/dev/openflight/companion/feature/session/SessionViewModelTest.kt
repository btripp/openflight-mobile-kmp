// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository()

    /** Starts disconnected, so the screen shows the phone's own history unless a test connects it. */
    private val piSession = FakePiSessionRepository(PiLinkState.Connecting)
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SessionViewModel(shots, settings, piSession, now = { "2026-09-24T18:30:05.123Z" })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region local history (plan R5a)

    @Test
    fun withNoShotsTheStateIsEmpty() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { true }
                assertThat(state.hasShots).isEqualTo(false)
                assertThat(state.allCount).isEqualTo(0)
                assertThat(state.clubChips).isEmpty()
                assertThat(state.selectedClub).isNull()
                assertThat(state.source).isEqualTo(SessionSource.LOCAL)
            }
        }

    @Test
    fun theAllTabAggregatesEveryClub() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.setHistory(listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0)))
                val state = awaitUntil { it.allCount == 2 }

                assertThat(state.hasShots).isTrue()
                assertThat(state.stats.shotCount).isEqualTo(2)
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
                assertThat(state.shots.map { it.id }).containsExactly(shotId(2), shotId(1))
                assertThat(state.shots.map { it.shotNumber }).containsExactly(2, 1)
            }
        }

    @Test
    fun selectingAClubFiltersTheStatsToThatClubOnly() =
        runTest {
            shots.setHistory(listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0)))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                val state = awaitUntil { it.selectedClub == "7-iron" }

                assertThat(state.stats.shotCount).isEqualTo(1)
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(120.0)
                // allCount, the chips and the rows stay over the full history, not the filtered tab.
                assertThat(state.allCount).isEqualTo(2)
                assertThat(state.shots.size).isEqualTo(2)
            }
        }

    @Test
    fun selectingNullReturnsToTheAllTab() =
        runTest {
            shots.setHistory(listOf(shot(1, club = "7-iron")))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                awaitUntil { it.selectedClub == "7-iron" }

                viewModel.onEvent(SessionEvent.SelectClub(null))
                val state = awaitUntil { it.selectedClub == null }
                assertThat(state.stats.shotCount).isEqualTo(1)
            }
        }

    @Test
    fun theUiStateCarriesTheSavedUnitPreference() =
        runTest {
            settings.units.value = UnitSystem.METRIC
            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { it.units == UnitSystem.METRIC }.units).isEqualTo(UnitSystem.METRIC)
            }
        }

    @Test
    fun localRowsCarryThePiDetailWhenItIsKnown() =
        runTest {
            shots.setHistory(listOf(shot(1)))
            piSession.shotDetails.value = mapOf(timestamp(1) to detail(1, playerName = "Ann"))

            viewModel.uiState.testIgnoringRest {
                val row = awaitUntil { it.shots.isNotEmpty() }.shots.single()

                assertThat(row.playerName).isEqualTo("Ann")
                assertThat(row.enrichment?.launchAngleConfidence).isEqualTo(ConfidenceLevel.MEDIUM)
            }
        }

    @Test
    fun deletingALocalRowGoesToTheRepositoryByEventId() =
        runTest {
            shots.setHistory(listOf(shot(2), shot(1)))

            viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))

            assertThat(shots.deleteShotCalls).containsExactly(shotId(1))
            assertThat(shots.deleteShotByTimestampCalls).isEmpty()
        }

    @Test
    fun clearHistoryEventGoesToTheRepository() =
        runTest {
            viewModel.onEvent(SessionEvent.ClearHistory)

            assertThat(shots.clearHistoryCalls).isEqualTo(1)
        }

    @Test
    fun exportCsvProducesTheFullHistoryOldestFirstRegardlessOfTheSelectedTab() =
        runTest {
            shots.setHistory(listOf(shot(2, club = "7-iron"), shot(1, club = "driver")))
            viewModel.onEvent(SessionEvent.SelectClub("7-iron"))

            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.ExportCsv)
                val effect = awaitItem() as SessionEffect.CsvReady

                val lines = effect.csv.lines()
                // shot 1 (oldest, driver) is row 1; shot 2 (newest, 7-iron) is row 2 -- unaffected by
                // the "7-iron" tab selection, matching the web UI's export.
                assertThat(lines[1].startsWith("1,${shotId(1)}")).isTrue()
                assertThat(lines[2].startsWith("2,${shotId(2)}")).isTrue()
                // No Pi detail known: the R5a columns only.
                assertThat(lines[0].endsWith("spin_axis_deg")).isTrue()
                assertThat(effect.filename).isEqualTo("openflight-shots-2026-09-24T18-30-05-123Z.csv")
            }
        }

    // endregion

    // region Pi session (plan R6b)

    @Test
    fun whileThePiIsConnectedTheSessionAndStatsComeFromThePi() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(2, club = "7-iron", ballSpeedMph = 120.0), detail(1)))
            piSession.stats.value =
                SessionStats(shotCount = 2, avgBallSpeed = 130.0, maxBallSpeed = 140.0, avgCarryEst = 250.0)

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.source == SessionSource.PI && it.allCount == 2 }

                assertThat(state.shots.map { it.id }).containsExactly(timestamp(2), timestamp(1))
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(130.0) // The server's, not recomputed.
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
                assertThat(state.shots.first().enrichment).isNotNull()

                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                val tab = awaitUntil { it.selectedClub == "7-iron" }
                assertThat(tab.stats.shotCount).isEqualTo(1)
                assertThat(tab.stats.avgBallSpeedMph).isEqualTo(120.0)
            }
        }

    @Test
    fun whenTheLinkDropsTheScreenFallsBackToTheLocalHistory() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(2), detail(1)))

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.source == SessionSource.PI }

                piSession.linkState.value = PiLinkState.Reconnecting(1, 1_000, "closed")

                val state = awaitUntil { it.source == SessionSource.LOCAL }
                assertThat(state.shots.map { it.id }).containsExactly(shotId(9))
            }
        }

    @Test
    fun aSwingSpeedTabShowsSwingStats() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(swingRep(3, 95.0), swingRep(2, 100.0), swingRep(1, 90.0)))

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.swingStats != null }

                assertThat(state.swingStats?.count).isEqualTo(3)
                assertThat(state.swingStats?.lastSpeedMph).isEqualTo(95.0)
                assertThat(state.swingStats?.bestSpeedMph).isEqualTo(100.0)
                assertThat(state.shots.first().isSwingSpeed).isTrue()
                assertThat(state.shots.first().implementLabel).isEqualTo("Stack 100g")
            }
        }

    @Test
    fun deletingAPiRowGoesToTheRepositoryByTimestamp() =
        runTest {
            shots.setHistory(listOf(shot(9)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(detail(1)))

            viewModel.onEvent(SessionEvent.DeleteShot(timestamp(1)))

            assertThat(shots.deleteShotByTimestampCalls).containsExactly(timestamp(1))
            assertThat(shots.deleteShotCalls).isEmpty()
        }

    @Test
    fun aPiDeleteErrorBecomesAMessage() =
        runTest {
            viewModel.effects.test {
                piSession.notices.emit(PiNotice.DeleteShotFailed("Shot not found"))

                assertThat(awaitItem()).isEqualTo(SessionEffect.Message("Shot not found"))
            }
        }

    @Test
    fun exportWhileConnectedUsesThePiSessionWithTheWebExportColumns() =
        runTest {
            shots.setHistory(listOf(shot(1)))
            piSession.linkState.value = PiLinkState.Connected
            piSession.setSession(listOf(swingRep(2, 97.4), detail(1)))

            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.ExportCsv)
                val lines = (awaitItem() as SessionEffect.CsvReady).csv.lines()

                assertThat(
                    lines[0].endsWith(
                        "player,mode,implement,openflight_speed_mph,reading_count," +
                            "trigger_speed_mph,duration_ms,peak_magnitude",
                    ),
                ).isTrue()
                // Row 1 is the oldest, and keeps the phone's event id for the shot it also received.
                assertThat(lines[1].startsWith("1,${shotId(1)},${timestamp(1)},driver,")).isTrue()
                assertThat(lines[2]).isEqualTo(
                    "2,,${timestamp(
                        2,
                    )},Swing Speed,97.4,97.4,,,,,,,,Ann,swing-speed,Stack 100g,97.4,5,80.1,1200.0,210.5",
                )
            }
        }

    @Test
    fun simulateShotIsVisibleOnlyInMockModeAndSendsTheCommand() =
        runTest {
            piSession.linkState.value = PiLinkState.Connected

            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { true }.showSimulateShot).isFalse()

                piSession.mockMode.value = true
                piSession.triggerStatus.value = TriggerStatus(mode = "swing-speed")
                val state = awaitUntil { it.showSimulateShot && it.simulateLabel == SessionUiState.SIMULATE_SWING }
                assertThat(state.simulateAvailability).isEqualTo(PiFeatureAvailability.Available)
            }

            viewModel.onEvent(SessionEvent.SimulateShot)

            assertThat(piSession.commands).containsExactly("simulate_shot")
        }

    @Test
    fun simulateShotWithoutALinkReportsWhy() =
        runTest {
            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.SimulateShot)

                assertThat(awaitItem()).isEqualTo(SessionEffect.Message("Not connected to the Pi's live session yet."))
            }
            assertThat(viewModel.uiState.value.simulateAvailability)
                .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED))
        }

    // endregion

    /** Like `test`, but tolerates the extra intermediate states `combine` may emit after the assertions. */
    private suspend fun <T> Flow<T>.testIgnoringRest(block: suspend ReceiveTurbine<T>.() -> Unit) =
        test {
            block()
            cancelAndIgnoreRemainingEvents()
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
