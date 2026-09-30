// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClubDetailViewModelTest {
    private val history = FakeShotHistoryRepository()
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        club: GolfClub = GolfClub.IRON_7,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = ClubDetailViewModel(
        club.wireValue,
        history,
        FakeConditionsRepository(),
        settings,
        computeDispatcher = dispatcher,
    )

    @Test
    fun theMetricCarrySpreadIsRoundedOnce() =
        runTest {
            // A spread of 1.58 yds (1.45 m); rounding the yards first would show "± 2 m".
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))

            viewModel(settings = FakeSettingsRepository(units = UnitSystem.METRIC)).uiState.testIgnoringRest {
                val state = awaitUntil { it.shotCount == 5 }
                assertThat(state.summaryLines.first()).isEqualTo("146 m ± 1 m carry")
            }
        }

    @Test
    fun aClubWithoutShotsIsLoadedButEmpty() =
        runTest {
            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded }
                assertThat(state.clubName).isEqualTo("7-Iron")
                assertThat(state.shotCount).isEqualTo(0)
                assertThat(state.histogram).isEmpty()
                assertThat(state.dispersion).isNull()
            }
        }

    @Test
    fun theDetailSummarisesHistogramsAndListsRecentShots() =
        runTest {
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.shotCount == 5 }
                assertThat(state.summaryLines.first()).isEqualTo("160 yds ± 2 yds carry")
                assertThat(state.histogram.map { it.count }).containsExactly(2, 3)
                assertThat(state.recentShots.size).isEqualTo(5)
                assertThat(state.recentShots.first().totalLabel).isNotNull()
                assertThat(state.dispersion).isNotNull()
                assertThat(state.totalLabel).isNotNull()
            }
        }

    @Test
    fun aPossibleBadReadIsListedButLeftOutOfTheStats() =
        runTest {
            val shots = (fiveShots(GolfClub.IRON_7, 160.0) + fiveShots(GolfClub.IRON_7, 161.0)).toMutableList()
            shots.add(0, historyShot(GolfClub.IRON_7, 240.0))
            history.put("s1", shots)

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.loaded && it.shotCount > 0 }
                assertThat(state.shotCount).isEqualTo(10)
                assertThat(state.excludedLabel).isEqualTo("1 possible bad read left out")
                assertThat(state.recentShots.first().possibleBadRead).isTrue()
            }
        }

    @Test
    fun theLastSessionsWindowShowsOnlyRecentSessions() =
        runTest {
            // 155 ± 2: near enough to the rest that none is a possible bad read.
            history.put("old", fiveShots(GolfClub.IRON_7, 155.0, "old"), startedAtEpochMillis = 0)
            (1..5).forEach {
                history.put(
                    "s$it",
                    fiveShots(GolfClub.IRON_7, 160.0, "s$it"),
                    startedAtEpochMillis =
                        it * 1000L,
                )
            }
            val vm = viewModel()

            vm.uiState.testIgnoringRest {
                assertThat(awaitUntil { it.shotCount > 0 }.shotCount).isEqualTo(30)
                vm.onEvent(ClubDetailEvent.SelectWindow(AnalysisWindow.LAST_5_SESSIONS))
                val recent = awaitUntil { it.window == AnalysisWindow.LAST_5_SESSIONS && it.shotCount > 0 }
                assertThat(recent.shotCount).isEqualTo(25)
            }
        }

    @Test
    fun histogramBinsAreFiveYardsWithEmptyBinsKept() {
        val bins = carryHistogram(listOf(150.0, 151.0, 162.0), UnitSystem.IMPERIAL)

        assertThat(bins.map { it.count }).containsExactly(2, 0, 1)
        assertThat(bins.map { it.label }).containsExactly("150–155", "155–160", "160–165")
        assertThat(bins.first().fraction).isCloseTo(1.0, 1e-9)
        assertThat(bins.last().fraction).isCloseTo(0.5, 1e-9)
    }

    @Test
    fun anExactBinEdgeFallsInTheBinAbove() {
        val bins = carryHistogram(listOf(150.0, 160.0), UnitSystem.IMPERIAL)

        assertThat(bins.map { it.count }).containsExactly(1, 0, 1)
    }
}
