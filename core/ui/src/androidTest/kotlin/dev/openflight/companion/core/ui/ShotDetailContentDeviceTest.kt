// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.ShotDetailState
import dev.openflight.companion.core.insights.ShotDetailTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ShotDetailContentDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var closes = 0
    private var ranges = 0

    private fun show(state: ShotDetailState = previewShotDetail()) {
        composeRule.setContent {
            OfTheme {
                // The sheet scrolls its content; so does this host, so every metric can be reached.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ShotDetailContent(
                        state = state,
                        onClose = { closes++ },
                        onViewOnRange = { ranges++ },
                        reduceMotion = true,
                    )
                }
            }
        }
    }

    @Test
    fun givenAPushFade_whenShown_thenTheShapeFlightAndMetricsShow() {
        show()

        composeRule.onNodeWithText("Shot 12 · 7-Iron").assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.SHAPE).assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.FLIGHT).assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.metric("Carry")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.metric("Club path")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whenTheFlightIsDraggedAndAnotherViewPicked_thenItStillDraws() {
        show()

        composeRule.onNodeWithText("Drag to orbit · tap to replay").assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.FLIGHT).performTouchInput { swipeLeft() }
        composeRule.onNodeWithText("Top").performClick()

        composeRule.onNodeWithText("Tap to replay").assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.FLIGHT).performClick().assertIsDisplayed()
    }

    @Test
    fun whenRangeAndCloseAreTapped_thenTheyAreReported() {
        show()

        composeRule.onNodeWithTag(ShotDetailTestTags.VIEW_ON_RANGE).performClick()
        composeRule.onNodeWithTag(ShotDetailTestTags.CLOSE).performClick()

        assertEquals(1, ranges)
        assertEquals(1, closes)
    }

    @Test
    fun givenNoFlight_whenShown_thenTheNotesExplainAndRangeIsHidden() {
        show(previewShotDetail().copy(flight = null, notes = listOf("A swing-speed rep has no ball flight to draw.")))

        composeRule.onNodeWithTag(ShotDetailTestTags.NOTES).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.VIEW_ON_RANGE).assertDoesNotExist()
        composeRule.onNodeWithTag(ShotDetailTestTags.FLIGHT).assertDoesNotExist()
    }
}
