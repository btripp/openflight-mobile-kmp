// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [OfListDetailPane] composes each slot once and moves it when the layout flips (the window class
 * changes, or a selection opens the detail), so the slots' remembered state survives. Before, each
 * branch invoked the slot itself and a flip reset it (compose-rules' content-slot-reused).
 */
@RunWith(AndroidJUnit4::class)
class OfListDetailPaneDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var windowClass by mutableStateOf(OfWindowClass.COMPACT)
    private var hasSelection by mutableStateOf(false)

    private fun show() {
        composeRule.setContent {
            OfTheme {
                OfListDetailPane(
                    hasSelection = hasSelection,
                    windowClass = windowClass,
                    list = { Counter(LIST_COUNTER) },
                    detail = { Counter(DETAIL_COUNTER) },
                )
            }
        }
    }

    @Test
    fun givenAListWithState_whenTheWindowExpands_thenTheListKeepsItsState() {
        show()
        composeRule.onNodeWithTag(LIST_COUNTER).performClick().performClick()

        windowClass = OfWindowClass.EXPANDED
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(OfListDetailPaneTags.DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(LIST_COUNTER).assertTextEquals("2")
    }

    @Test
    fun givenADetailWithState_whenTheWindowExpands_thenTheDetailKeepsItsState() {
        hasSelection = true
        show()
        composeRule.onNodeWithTag(DETAIL_COUNTER).performClick()

        windowClass = OfWindowClass.EXPANDED
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(OfListDetailPaneTags.LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(DETAIL_COUNTER).assertTextEquals("1")
    }

    @Test
    fun givenTwoPanesWithState_whenTheWindowNarrows_thenTheShownPaneKeepsItsState() {
        windowClass = OfWindowClass.EXPANDED
        show()
        composeRule
            .onNodeWithTag(LIST_COUNTER)
            .performClick()
            .performClick()
            .performClick()

        windowClass = OfWindowClass.MEDIUM
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DETAIL_COUNTER).assertDoesNotExist()
        composeRule.onNodeWithTag(LIST_COUNTER).assertTextEquals("3")
    }

    private companion object {
        const val LIST_COUNTER = "list.counter"
        const val DETAIL_COUNTER = "detail.counter"
    }
}

/** A button whose label counts its clicks in remembered (not saved) state. */
@Composable
private fun Counter(
    tag: String,
    modifier: Modifier = Modifier,
) {
    var count by remember { mutableIntStateOf(0) }
    OfTextButton(text = "$count", onClick = { count++ }, modifier = modifier.testTag(tag))
}
