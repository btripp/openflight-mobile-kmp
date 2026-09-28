// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Issue #15: [OfDropdownMenu]'s optional second section behind an expanding row. */
@RunWith(AndroidJUnit4::class)
class OfDropdownMenuDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val selections = mutableListOf<String>()

    private fun show(
        selected: String,
        moreOptions: List<String>,
    ) {
        composeRule.setContent {
            OfTheme {
                OfDropdownMenu(
                    label = "",
                    selected = selected,
                    options = listOf("Driver", "7-Iron"),
                    onSelect = { selections += it },
                    moreOptions = moreOptions,
                    moreOptionsLabel = "All clubs…",
                    modifier = Modifier.testTag(FIELD),
                )
            }
        }
    }

    @Test
    fun givenMoreOptions_whenOpened_thenTheyStayHiddenUntilTheRowIsTapped() {
        show(selected = "Driver", moreOptions = listOf("4-Iron"))

        composeRule.onNodeWithTag(FIELD).performClick()
        composeRule.onNodeWithText("7-Iron").assertIsDisplayed()
        composeRule.onAllNodesWithText("4-Iron").assertCountEquals(0)

        composeRule.onNodeWithTag(OfDropdownMenuTags.MORE).performClick()
        composeRule.onNodeWithText("4-Iron").assertIsDisplayed().performClick()

        assertEquals(listOf("4-Iron"), selections)
    }

    @Test
    fun givenTheSelectionIsAMoreOption_whenOpened_thenTheSectionStartsExpanded() {
        show(selected = "4-Iron", moreOptions = listOf("4-Iron", "2-Iron"))

        composeRule.onNodeWithTag(FIELD).performClick()

        composeRule.onNodeWithText("2-Iron").assertIsDisplayed()
    }

    @Test
    fun givenNoMoreOptions_whenOpened_thenThereIsNoExpandingRow() {
        show(selected = "Driver", moreOptions = emptyList())

        composeRule.onNodeWithTag(FIELD).performClick()

        composeRule.onNodeWithText("7-Iron").assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(OfDropdownMenuTags.MORE)).assertCountEquals(0)
    }

    private companion object {
        const val FIELD = "dropdown_field"
    }
}
