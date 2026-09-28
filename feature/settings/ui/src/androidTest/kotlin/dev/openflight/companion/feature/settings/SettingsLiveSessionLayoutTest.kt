// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.pi.PiLinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #7: a long live-session status (the Socket.IO link's reconnect reason) took the whole
 * connection row, so the "Live session" label was laid out one letter per line.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLiveSessionLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        link: PiLinkState,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme { SettingsScreen(uiState = previewSettingsState(link = link), onEvent = {}, onBack = {}) }
            }
        }
        composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_STATUS).performScrollTo()
    }

    @Test
    fun givenLongLiveSessionStatus_whenSettingsShown_thenLabelKeepsItsWidth() {
        show(LONG_STATUS)

        assertLabelIsNotSqueezed()
        assertStatusIsUnderTheLabel()
        assertStatusFitsTheCard(LONG_STATUS.description)
    }

    @Test
    fun givenLongLiveSessionStatusAt200PercentText_whenSettingsShown_thenLabelKeepsItsWidth() {
        show(LONG_STATUS, fontScale = 2f)

        assertLabelIsNotSqueezed()
        assertStatusIsUnderTheLabel()
        assertStatusFitsTheCard(LONG_STATUS.description)
    }

    /** A status too long to sit beside the label moves to its own line instead of squeezing it. */
    private fun assertStatusIsUnderTheLabel() {
        val label = composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_LABEL).getUnclippedBoundsInRoot()
        val status = composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_STATUS).getUnclippedBoundsInRoot()
        assertTrue(status.top >= label.bottom, "the pill ($status) should wrap under the label ($label)")
    }

    @Test
    fun givenConnected_whenSettingsShown_thenTheStatusSitsBesideTheLabel() {
        show(PiLinkState.Connected)

        assertLabelIsNotSqueezed()
        val label = composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_LABEL).getUnclippedBoundsInRoot()
        val status = composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_STATUS).getUnclippedBoundsInRoot()
        assertTrue(status.left >= label.right, "the pill ($status) should sit to the right of the label ($label)")
        assertTrue(status.top < label.bottom && status.bottom > label.top, "the pill should share the label's line")
        assertStatusFitsTheCard(PiLinkState.Connected.description)
    }

    /** "Live session" stays one line, or at most breaks between its two words, never mid-word. */
    private fun assertLabelIsNotSqueezed() {
        val layout = textLayout(composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_LABEL, useUnmergedTree = true))
        val text = layout.layoutInput.text.text
        assertTrue(layout.lineCount <= text.split(' ').size, "\"$text\" wraps into ${layout.lineCount} lines")
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue(
                text[end - 1].isWhitespace() || text[end].isWhitespace(),
                "\"$text\" breaks mid-word after \"${text.substring(0, end)}\"",
            )
        }
    }

    /** The pill's text wraps inside the connection card: nothing spills out or gets cut off. */
    private fun assertStatusFitsTheCard(description: String) {
        val card = composeRule.onNodeWithTag(SettingsTestTags.CONNECTION).getUnclippedBoundsInRoot()
        val status = composeRule.onNodeWithTag(SettingsUiTags.LIVE_SESSION_STATUS).getUnclippedBoundsInRoot()
        assertTrue(status.left >= card.left && status.right <= card.right, "the pill ($status) leaves the card ($card)")
        val text =
            composeRule.onNode(
                hasText(description) and hasAnyAncestor(hasTestTag(SettingsUiTags.LIVE_SESSION_STATUS)),
                useUnmergedTree = true,
            )
        val textBounds = text.getUnclippedBoundsInRoot()
        assertTrue(textBounds.right <= status.right, "\"$description\" ($textBounds) runs past the pill ($status)")
        assertFalse(textLayout(text).didOverflowHeight, "\"$description\" is cut off")
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        val LONG_STATUS = PREVIEW_LONG_LINK_STATUS
    }
}
