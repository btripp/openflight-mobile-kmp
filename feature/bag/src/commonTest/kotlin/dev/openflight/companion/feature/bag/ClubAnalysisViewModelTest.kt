// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.openflight.companion.core.insights.GapInsight
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.testing.FakeBagRepository
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
class ClubAnalysisViewModelTest {
    private val bags = FakeBagRepository()
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

    private fun viewModel(settings: FakeSettingsRepository = FakeSettingsRepository()) =
        ClubAnalysisViewModel(
            bags,
            history,
            FakeConditionsRepository(),
            settings,
            computeDispatcher = dispatcher,
        )

    @Test
    fun theMetricCarrySpreadIsRoundedOnce() =
        runTest {
            // A spread of 1.58 yds (1.45 m); rounding the yards first would show "± 2 m".
            bags.seedDefaultBagIfEmpty()
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))

            viewModel(FakeSettingsRepository(units = UnitSystem.METRIC)).uiState.testIgnoringRest {
                val state = awaitUntil { it.bars.isNotEmpty() }
                assertThat(state.bars.single().plusMinusLabel).isEqualTo("± 1 m")
            }
        }

    @Test
    fun barsAreRankedLongestFirstAndScaledToTheLongest() =
        runTest {
            bags.seedDefaultBagIfEmpty()
            history.put(
                "s1",
                fiveShots(GolfClub.IRON_8, 150.0) + fiveShots(GolfClub.DRIVER, 250.0) +
                    fiveShots(GolfClub.IRON_7, 160.0),
            )

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.bars.size == 3 }
                assertThat(
                    state.bars.map { it.club },
                ).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.IRON_8)
                assertThat(state.bars.first().fraction).isCloseTo(1.0, 1e-9)
                assertThat(state.bars.last().fraction).isCloseTo(0.6, 1e-6)
                assertThat(state.bars.first().estimated).isFalse()
                assertThat(state.bars.first().valueLabel).isEqualTo("250 yds")
            }
        }

    @Test
    fun twoClubsFiveYardsApartGetATooTightInsight() =
        runTest {
            bags.seedDefaultBagIfEmpty()
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0) + fiveShots(GolfClub.IRON_8, 155.0))

            viewModel().uiState.testIgnoringRest {
                val state = awaitUntil { it.bars.size == 2 }
                assertThat(state.insights.single()).isInstanceOf(GapInsight.TooTight::class)
                assertThat(state.insightTexts.single()).isEqualTo(
                    "7-Iron and 8-Iron are only 5 yds apart. Consider a different loft or dropping one.",
                )
            }
        }

    @Test
    fun theTotalIsAnEstimateLongerThanTheCarry() =
        runTest {
            bags.seedDefaultBagIfEmpty()
            history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))
            val vm = viewModel()

            vm.uiState.testIgnoringRest {
                awaitUntil { it.bars.isNotEmpty() }
                vm.onEvent(ClubAnalysisEvent.SelectMetric(AnalysisMetric.TOTAL))
                val state = awaitUntil { it.metric == AnalysisMetric.TOTAL && it.bars.isNotEmpty() }
                assertThat(state.bars.single().estimated).isTrue()
                assertThat(state.bars.single().valueYards).isGreaterThan(160.0)
            }
        }

    @Test
    fun theLastSessionsWindowLeavesOlderSessionsOut() =
        runTest {
            bags.seedDefaultBagIfEmpty()
            // Six sessions: the oldest carries 150 (near enough not to be a bad read), the rest 160.
            history.put("old", fiveShots(GolfClub.IRON_7, 150.0, "old"), startedAtEpochMillis = 0)
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
                val lifetime = awaitUntil { it.bars.isNotEmpty() }
                assertThat(lifetime.bars.single().valueYards < 159.0).isTrue()
                vm.onEvent(ClubAnalysisEvent.SelectWindow(AnalysisWindow.LAST_5_SESSIONS))
                val recent = awaitUntil { it.window == AnalysisWindow.LAST_5_SESSIONS }
                assertThat(recent.bars.single().valueYards).isCloseTo(160.0, 1e-6)
            }
        }
}
