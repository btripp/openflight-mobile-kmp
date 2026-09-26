// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.designsystem.OfListDetailPaneTags
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeBagRepository
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** My Bag, Club Analysis and the tablet list-detail (plan F5), over the real ViewModels and fakes. */
@RunWith(AndroidJUnit4::class)
class BagScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val bags = FakeBagRepository()
    private val history = FakeShotHistoryRepository()
    private val conditions = FakeConditionsRepository()
    private val settings = FakeSettingsRepository()

    @Test
    fun given_emptyBag_when_open_then_defaultBagSeeded() {
        val viewModel = BagViewModel(bags, history, conditions, settings)
        composeRule.setContent {
            OfTheme {
                BagRoute(
                    onBack = {},
                    onOpenAnalysis = {},
                    windowClass = OfWindowClass.COMPACT,
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(TIMEOUT) { bags.state.value.isNotEmpty() }
        composeRule.onNodeWithTag(BagTestTags.BAG_NAME).assert(hasText("My Bag"))
        composeRule.onNodeWithTag(BagTestTags.club(GolfClub.DRIVER.wireValue)).assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                BagTestTags.LIST,
            ).performScrollToNode(hasTestTag(BagTestTags.club(GolfClub.LOB_WEDGE.wireValue)))
        composeRule.onNodeWithTag(BagTestTags.club(GolfClub.LOB_WEDGE.wireValue)).assertIsDisplayed()
        assertEquals(
            14,
            bags.state.value
                .single()
                .clubs.size,
        )
    }

    @Test
    fun given_twoClubs5ydApart_when_analysis_then_tooTightInsightShown() {
        runBlocking { bags.seedDefaultBagIfEmpty() }
        history.put("s1", fiveShots(GolfClub.IRON_7, 160.0) + fiveShots(GolfClub.IRON_8, 155.0))
        val viewModel = ClubAnalysisViewModel(bags, history, conditions, settings)
        composeRule.setContent {
            OfTheme { ClubAnalysisRoute(onBack = {}, onOpenClub = {}, viewModel = viewModel) }
        }

        composeRule.waitUntil(
            TIMEOUT,
        ) { composeRule.onAllNodesWithTag(BagTestTags.INSIGHT).fetchSemanticsNodes().isNotEmpty() }
        composeRule
            .onNodeWithTag(BagTestTags.INSIGHT)
            .assert(hasText("7-Iron and 8-Iron are only 5 yds apart", substring = true))
        composeRule.onNodeWithTag(BagTestTags.bar(GolfClub.IRON_7.wireValue)).assertIsDisplayed()
    }

    @Test
    fun given_totalMetric_when_selected_then_estimatedBadgeShown() {
        runBlocking { bags.seedDefaultBagIfEmpty() }
        history.put("s1", fiveShots(GolfClub.IRON_7, 160.0))
        val viewModel = ClubAnalysisViewModel(bags, history, conditions, settings)
        composeRule.setContent {
            OfTheme { ClubAnalysisRoute(onBack = {}, onOpenClub = {}, viewModel = viewModel) }
        }
        composeRule.waitUntil(TIMEOUT) {
            viewModel.uiState.value.bars
                .isNotEmpty()
        }

        composeRule.onNode(hasText(AnalysisMetric.TOTAL.label)).performClick()

        composeRule.waitUntil(TIMEOUT) { viewModel.uiState.value.metric == AnalysisMetric.TOTAL }
        composeRule.onNodeWithTag(BagTestTags.ESTIMATED, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun given_expandedWindow_when_clubSelected_then_listAndDetailSideBySide() {
        composeRule.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            OfTheme {
                BagScreen(
                    uiState = state(),
                    onEvent = {},
                    onBack = {},
                    onOpenAnalysis = {},
                    selectedClub = selected,
                    onSelectClub = { selected = it },
                    editingConditions = false,
                    onEditConditions = {},
                    windowClass = OfWindowClass.EXPANDED,
                    detail = { wire -> OfText(text = "Detail for $wire") },
                )
            }
        }

        composeRule.onNodeWithTag(BagTestTags.DETAIL_PLACEHOLDER).assertIsDisplayed()
        // The list pane is 40% of the width, so on a phone-sized emulator its rows wrap tall.
        composeRule.onNodeWithTag(BagTestTags.LIST).performScrollToNode(hasTestTag(BagTestTags.club("7-iron")))
        composeRule.onNodeWithTag(BagTestTags.club("7-iron")).performClick()

        composeRule.onNodeWithTag(OfListDetailPaneTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(OfListDetailPaneTags.DETAIL).assertIsDisplayed()
        composeRule.onNode(hasText("Detail for 7-iron")).assertIsDisplayed()
        composeRule.onNodeWithTag(BagTestTags.club("7-iron")).assertIsDisplayed()
    }

    @Test
    fun given_compactWindow_when_clubTapped_then_detailReplacesListUntilBack() {
        composeRule.setContent {
            var selected by remember { mutableStateOf<String?>(null) }
            OfTheme {
                BagScreen(
                    uiState = state(),
                    onEvent = {},
                    onBack = {},
                    onOpenAnalysis = {},
                    selectedClub = selected,
                    onSelectClub = { selected = it },
                    editingConditions = false,
                    onEditConditions = {},
                    windowClass = OfWindowClass.COMPACT,
                    detail = { wire -> OfText(text = "Detail for $wire") },
                )
            }
        }

        composeRule.onNodeWithTag(BagTestTags.club("7-iron")).performClick()
        composeRule.onNode(hasText("Detail for 7-iron")).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfListDetailPaneTags.LIST).assertCountEquals(0)

        composeRule.onNodeWithTag(BagTestTags.DETAIL_DONE).performClick()
        composeRule.onNodeWithTag(BagTestTags.club("7-iron")).assertIsDisplayed()
    }

    @Test
    fun given_conditionsEditor_when_altitudeSaved_then_summaryShowsIt() {
        val viewModel = BagViewModel(bags, history, conditions, settings)
        composeRule.setContent {
            OfTheme {
                BagRoute(
                    onBack = {},
                    onOpenAnalysis = {},
                    windowClass = OfWindowClass.COMPACT,
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitUntil(TIMEOUT) { viewModel.uiState.value.loaded }

        composeRule.onNodeWithTag(BagTestTags.CONDITIONS_EDIT).performClick()
        composeRule.onNodeWithTag(BagTestTags.CONDITIONS_ALTITUDE).performTextReplacement("5280")
        // The sheet may still be animating in, or the keyboard may cover Save: click through the
        // button's semantics action rather than a touch at its (moving) position.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BagTestTags.CONDITIONS_SAVE).performSemanticsAction(SemanticsActions.OnClick)

        composeRule.waitUntil(TIMEOUT) { conditions.conditions.value.altitudeMeters > 1600 }
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithTag(BagTestTags.CONDITIONS_SAVE).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(BagTestTags.CONDITIONS_SUMMARY).assert(hasText("5,280 ft", substring = true))
    }

    private fun state(): BagUiState =
        BagUiState(
            loaded = true,
            bagId = "bag-1",
            bagName = "My Bag",
            clubs =
                listOf(GolfClub.DRIVER, GolfClub.IRON_7).mapIndexed { index, club ->
                    BagClubRow(
                        id = "club-$index",
                        club = club,
                        wireValue = club.wireValue,
                        name = club.displayName,
                        shortLabel = club.shortLabel,
                        makeModel = null,
                        make = null,
                        model = null,
                        loftDeg = null,
                        avgCarryYards = null,
                        carryLabel = "—",
                        plusMinusLabel = null,
                        shotCountLabel = "0 shots",
                        colorIndex = index,
                        gap = null,
                    )
                },
        )

    private var nextId = 0L

    private fun fiveShots(
        club: GolfClub,
        carry: Double,
    ): List<HistoryShot> =
        (-2..2).map { delta ->
            val id = ++nextId
            HistoryShot(
                id = id,
                sessionId = "s1",
                eventId = "e$id",
                detail =
                    ShotDetail(
                        timestamp = "2026-09-25T10:00:0$id",
                        club = club.wireValue,
                        ballSpeedMph = (carry + delta) * 0.75,
                        estimatedCarryYards = carry + delta,
                        launchAngleVertical = 16.0,
                        launchAngleHorizontal = 0.0,
                        spinRpm = 6500.0,
                        spinAxisDeg = 0.0,
                    ),
            )
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
