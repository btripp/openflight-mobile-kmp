// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Plan F8d: a Club Detail recent shot's "Range" hands its session and stored row id to navigation. */
@RunWith(AndroidJUnit4::class)
class ClubDetailViewOnRangeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state =
        ClubDetailUiState(
            loaded = true,
            wireValue = "7-iron",
            clubName = "7-Iron",
            shotCount = 2,
            recentShots =
                listOf(
                    RecentShotRow(42, "2026-09-25 10:00", "160 yds", "172 yds", null, false, "session-a"),
                    RecentShotRow(7, "2026-09-21 18:12", "158 yds", null, null, false, "session-b"),
                ),
        )

    @Test
    fun given_recentShot_when_rangeTapped_then_itsSessionAndRowOpen() {
        val viewed = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            OfTheme {
                ClubDetailScreen(
                    uiState = state,
                    onEvent = {},
                    onBack = {},
                    onViewOnRange = { sessionId, shotId -> viewed += sessionId to shotId },
                )
            }
        }

        val tag = BagTestTags.viewOnRange(7)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()

        assertEquals(listOf("session-b" to "7"), viewed)
    }

    @Test
    fun given_noNavigation_when_shown_then_noRangeButtons() {
        composeRule.setContent { OfTheme { ClubDetailScreen(uiState = state, onEvent = {}, onBack = {}) } }

        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(BagTestTags.RECENT))
        composeRule.onNodeWithTag(BagTestTags.viewOnRange(42)).assertDoesNotExist()
    }
}
