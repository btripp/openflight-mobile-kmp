// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Plan F1b: on an expanded window, the selected shot moves out of the shot list into its own
 * detail pane beside it, instead of appearing inline in the list.
 */
@RunWith(AndroidJUnit4::class)
class SessionTabletLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SessionEvent>()

    private fun show(
        state: SessionUiState,
        windowClass: OfWindowClass,
    ) {
        composeRule.setContent {
            OfTheme {
                SessionScreen(
                    uiState = state,
                    onEvent = { events += it },
                    onBack = {},
                    windowClass = windowClass,
                )
            }
        }
    }

    private val withShots =
        SessionUiState(
            source = SessionSource.LOCAL,
            allCount = previewRows.size,
            clubChips = listOf(ClubChip("7-iron", 1), ClubChip("driver", 1)),
            stats =
                ClubStats(
                    shotCount = 2,
                    avgBallSpeedMph = 134.8,
                    maxBallSpeedMph = 151.4,
                    avgCarryYards = 214.6,
                    avgClubSpeedMph = null,
                    avgSmashFactor = null,
                ),
            shots = previewRows,
        )

    private val selectedDriver =
        SelectedShotCard(previewRows[1].id, 1, "Driver", carryYards = 264.0, spinRpm = 2380.0, clubSpeedMph = 103.2)

    @Test
    fun givenAnExpandedWindowWithNoSelection_whenShown_thenTheDetailPaneShowsAPlaceholder() {
        show(withShots, OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SessionTestTags.LIST_PANE).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.DETAIL_PANE).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.DETAIL_EMPTY).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SessionTestTags.SELECTED)).assertCountEquals(0)
    }

    @Test
    fun givenAnExpandedWindowWithASelection_whenShown_thenTheDetailShowsInTheSeparatePaneOnly() {
        show(withShots.copy(selectedShot = selectedDriver), OfWindowClass.EXPANDED)

        composeRule
            .onAllNodes(hasText("Shot 1 · Driver") and hasAnyAncestor(hasTestTag(SessionTestTags.DETAIL_PANE)))
            .assertCountEquals(1)
        composeRule
            .onAllNodes(hasText("Shot 1 · Driver") and hasAnyAncestor(hasTestTag(SessionTestTags.LIST_PANE)))
            .assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(SessionTestTags.DETAIL_EMPTY)).assertCountEquals(0)
    }

    @Test
    fun givenACompactWindowWithASelection_whenShown_thenTheDetailShowsInlineInTheOneList() {
        show(withShots.copy(selectedShot = selectedDriver), OfWindowClass.COMPACT)

        composeRule.onAllNodes(hasTestTag(SessionTestTags.LIST_PANE)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(SessionTestTags.DETAIL_PANE)).assertCountEquals(0)
        composeRule.onNodeWithTag(SessionTestTags.SELECTED).assertIsDisplayed()
    }

    @Test
    fun givenAnExpandedWindow_whenAShotRowIsTapped_thenSelectShotIsSent() {
        show(withShots, OfWindowClass.EXPANDED)
        val row = SessionTestTags.shot(previewRows[1].id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))

        composeRule.onNodeWithTag(row).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.SelectShot(previewRows[1].id)), events)
    }
}
