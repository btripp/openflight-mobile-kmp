// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Plan F8d: the Session screen's "Range" buttons hand the shot's row id to the app's navigation. */
@RunWith(AndroidJUnit4::class)
class SessionViewOnRangeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val viewed = mutableListOf<String>()

    private val driver = previewRows[1]
    private val swing = previewRow("swing-1", 3, "driver", 0.0, 0.0).copy(isSwingSpeed = true, swingSpeedMph = 98.0)

    private fun show(
        state: SessionUiState,
        windowClass: OfWindowClass,
        onViewOnRange: ((String) -> Unit)? = { viewed += it },
    ) {
        composeRule.setContent {
            OfTheme {
                SessionScreen(
                    uiState = state,
                    onEvent = {},
                    onBack = {},
                    windowClass = windowClass,
                    onViewOnRange = onViewOnRange,
                )
            }
        }
    }

    private fun state(): SessionUiState =
        SessionUiState(
            allCount = 3,
            shots = listOf(swing) + previewRows,
            selectedShot =
                SelectedShotCard(
                    driver.id,
                    1,
                    "Driver",
                    carryYards = 264.0,
                    spinRpm = 2380.0,
                    clubSpeedMph = 103.2,
                ),
        )

    @Test
    fun given_compact_when_rowAndCardRangeTapped_then_theirShotOpens() {
        show(state(), OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(SessionTestTags.SELECTED_VIEW_ON_RANGE).performClick()
        val row = SessionTestTags.viewOnRange(previewRows[0].id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        composeRule.onNodeWithTag(row).performClick()

        assertEquals(listOf(driver.id, previewRows[0].id), viewed)
    }

    @Test
    fun given_swingRep_when_shown_then_itHasNoRangeButton() {
        show(state(), OfWindowClass.COMPACT)

        val swingRow = SessionTestTags.shot(swing.id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(swingRow))
        composeRule.onNodeWithTag(SessionTestTags.viewOnRange(swing.id)).assertDoesNotExist()
    }

    @Test
    fun given_noNavigation_when_shown_then_noRangeButtons() {
        show(state(), OfWindowClass.COMPACT, onViewOnRange = null)

        composeRule.onNodeWithTag(SessionTestTags.SELECTED).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.SELECTED_VIEW_ON_RANGE).assertDoesNotExist()
    }

    @Test
    fun given_expanded_when_detailPaneRangeTapped_then_theSelectedShotOpens() {
        show(state(), OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SessionTestTags.DETAIL_PANE).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.SELECTED_VIEW_ON_RANGE).assertIsDisplayed().performClick()

        assertEquals(listOf(driver.id), viewed)
    }
}
