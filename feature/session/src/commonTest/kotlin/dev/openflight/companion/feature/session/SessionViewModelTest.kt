// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.UnitSystem
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
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SessionViewModel(shots, settings, now = { "2026-09-24T18:30:05.123Z" })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withNoShotsTheStateIsEmpty() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { true }
                assertThat(state.hasShots).isEqualTo(false)
                assertThat(state.allCount).isEqualTo(0)
                assertThat(state.clubChips).isEmpty()
                assertThat(state.selectedClub).isNull()
            }
        }

    @Test
    fun theAllTabAggregatesEveryClub() =
        runTest {
            viewModel.uiState.testIgnoringRest {
                shots.history.value =
                    listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0))
                val state = awaitUntil { it.allCount == 2 }

                assertThat(state.hasShots).isTrue()
                assertThat(state.stats.shotCount).isEqualTo(2)
                assertThat(state.clubChips).containsExactly(ClubChip("7-iron", 1), ClubChip("driver", 1))
            }
        }

    @Test
    fun selectingAClubFiltersTheStatsToThatClubOnly() =
        runTest {
            shots.history.value = listOf(shot(2, club = "7-iron", ballSpeedMph = 120.0), shot(1, ballSpeedMph = 150.0))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                val state = awaitUntil { it.selectedClub == "7-iron" }

                assertThat(state.stats.shotCount).isEqualTo(1)
                assertThat(state.stats.avgBallSpeedMph).isEqualTo(120.0)
                // allCount and the chips stay over the full history, not the filtered tab.
                assertThat(state.allCount).isEqualTo(2)
            }
        }

    @Test
    fun selectingNullReturnsToTheAllTab() =
        runTest {
            shots.history.value = listOf(shot(1, club = "7-iron"))

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
    fun deleteShotEventGoesToTheRepository() =
        runTest {
            viewModel.onEvent(SessionEvent.DeleteShot(shotId(1)))

            assertThat(shots.deleteShotCalls).containsExactly(shotId(1))
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
            shots.history.value = listOf(shot(2, club = "7-iron"), shot(1, club = "driver"))
            viewModel.onEvent(SessionEvent.SelectClub("7-iron"))

            viewModel.effects.test {
                viewModel.onEvent(SessionEvent.ExportCsv)
                val effect = awaitItem() as SessionEffect.CsvReady

                val lines = effect.csv.lines()
                // shot 1 (oldest, driver) is row 1; shot 2 (newest, 7-iron) is row 2 -- unaffected by
                // the "7-iron" tab selection, matching the web UI's export.
                assertThat(lines[1].startsWith("1,${shotId(1)}")).isTrue()
                assertThat(lines[2].startsWith("2,${shotId(2)}")).isTrue()
                assertThat(effect.filename).isEqualTo("openflight-shots-2026-09-24T18-30-05-123Z.csv")
            }
        }

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
