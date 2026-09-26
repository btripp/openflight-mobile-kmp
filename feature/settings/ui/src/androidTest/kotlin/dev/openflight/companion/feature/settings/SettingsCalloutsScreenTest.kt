// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.insights.CalloutField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The "Audio call-outs" settings section (plan F7), compact and expanded (plan A17: tests force
 * [OfWindowClass] directly rather than resizing the device window).
 */
@RunWith(AndroidJUnit4::class)
class SettingsCalloutsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SettingsEvent>()

    private fun show(
        state: SettingsUiState = previewSettingsState(),
        windowClass: OfWindowClass = OfWindowClass.COMPACT,
    ) {
        composeRule.setContent {
            OfTheme {
                SettingsScreen(uiState = state, onEvent = { events += it }, onBack = {}, windowClass = windowClass)
            }
        }
    }

    @Test
    fun givenCalloutsDisabled_whenShown_thenOnlyTheEnableSwitchShows() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS.copy(enabled = false)))

        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_ENABLED).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SettingsTestTags.CALLOUTS_TRIGGER)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(SettingsTestTags.CALLOUTS_VOICE)).assertCountEquals(0)
    }

    @Test
    fun givenCalloutsEnabled_whenTheSwitchIsTurnedOff_thenSetCalloutsEnabledIsSent() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        // The actual toggleable node is the Switch inside the tagged row, not the row itself
        // (OfSwitchRow's label isn't clickable) — same pattern SettingsScreenTest uses for
        // DEBUG_TOGGLE.
        composeRule
            .onNode(isToggleable() and hasAnyAncestor(hasTestTag(SettingsTestTags.CALLOUTS_ENABLED)))
            .performScrollTo()
            .performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.SetCalloutsEnabled(false)), events)
    }

    @Test
    fun givenCalloutsEnabled_whenGamesOnlyIsPicked_thenSetCalloutTriggerIsSent() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        composeRule.onNodeWithText("Games only").performScrollTo().performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.SetCalloutTrigger(CalloutTrigger.GAMES_ONLY)), events)
    }

    @Test
    fun givenCalloutsEnabled_whenPreviewIsTapped_thenPreviewCalloutIsSent() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_PREVIEW_BUTTON).performScrollTo().performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.PreviewCallout), events)
    }

    @Test
    fun givenAnUnselectedField_whenTapped_thenToggleCalloutFieldSelectsIt() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        composeRule
            .onNodeWithTag(SettingsTestTags.calloutFieldToggle(CalloutField.LAUNCH))
            .performScrollTo()
            .performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.ToggleCalloutField(CalloutField.LAUNCH)), events)
    }

    @Test
    fun givenASelectedFieldNotFirst_whenMoveUpIsTapped_thenMoveCalloutFieldIsSent() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        // BALL_SPEED is the second of the three preview-selected fields, so "up" is enabled.
        composeRule
            .onNodeWithTag(SettingsTestTags.calloutFieldMoveUp(CalloutField.BALL_SPEED))
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf<SettingsEvent>(SettingsEvent.MoveCalloutField(CalloutField.BALL_SPEED, up = true)),
            events,
        )
    }

    @Test
    fun givenTheLivePreviewLine_whenShown_thenItReflectsTheCurrentFields() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS))

        composeRule
            .onNodeWithTag(SettingsTestTags.CALLOUTS_PREVIEW_TEXT)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(PREVIEW_CALLOUTS.previewText).assertIsDisplayed()
    }

    @Test
    fun givenAnExpandedWindow_whenShown_thenTheCalloutsCardStillShowsEveryControl() {
        show(previewSettingsState(callouts = PREVIEW_CALLOUTS), windowClass = OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_ENABLED).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_TRIGGER).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_VOICE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.CALLOUTS_RATE).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag(SettingsTestTags.calloutField(CalloutField.SMASH))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
