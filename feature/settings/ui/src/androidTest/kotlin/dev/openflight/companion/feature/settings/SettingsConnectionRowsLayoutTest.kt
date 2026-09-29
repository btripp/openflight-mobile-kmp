// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #54: at 200% text the connection card's "Shot stream" value (a DNS error that quotes the
 * host) was squeezed beside its label and broke inside the host name ("raspberrypi.loc" / "al").
 * The label/value rows now wrap only at spaces: a value that doesn't fit beside its label moves
 * under it, like the "Live session" row (issue #7).
 */
@RunWith(AndroidJUnit4::class)
class SettingsConnectionRowsLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        connectionState: ConnectionState,
        fontScale: Float = 1f,
        host: String = DEFAULT_HOST,
        width: Dp? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme {
                    Box(if (width != null) Modifier.width(width) else Modifier) {
                        SettingsScreen(
                            uiState = previewSettingsState(connectionState = connectionState, host = host),
                            onEvent = {},
                            onBack = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(SettingsUiTags.SHOT_STREAM_VALUE).performScrollTo()
    }

    @Test
    fun givenLongShotStreamError_whenLargeFont_thenHostIsNotBrokenMidWord() {
        show(LONG_ERROR, fontScale = 2f)

        assertBreaksOnlyAtSpaces(SettingsUiTags.SHOT_STREAM_VALUE)
        assertLineContains(SettingsUiTags.SHOT_STREAM_VALUE, "\"raspberrypi.local\":")
        assertBreaksOnlyAtSpaces(SettingsUiTags.SHOT_STREAM_LABEL)
        assertValueIsUnderItsLabel(SettingsUiTags.SHOT_STREAM_LABEL, SettingsUiTags.SHOT_STREAM_VALUE)
        assertValueFitsTheCard(SettingsUiTags.SHOT_STREAM_VALUE)
    }

    @Test
    fun givenLongShotStreamError_whenSettingsShown_thenHostIsNotBrokenMidWord() {
        show(LONG_ERROR)

        assertBreaksOnlyAtSpaces(SettingsUiTags.SHOT_STREAM_VALUE)
        assertLineContains(SettingsUiTags.SHOT_STREAM_VALUE, "\"raspberrypi.local\":")
        assertValueFitsTheCard(SettingsUiTags.SHOT_STREAM_VALUE)
    }

    /** A common 411 dp phone: the default host fits a line of its own, but not beside "Host". */
    @Test
    fun givenDefaultHostOnAPhone_whenLargeFont_thenHostStaysOnOneLine() {
        show(LONG_ERROR, fontScale = 2f, host = DEFAULT_HOST, width = PHONE_WIDTH)

        assertEquals(1, textLayout(SettingsUiTags.HOST_VALUE).lineCount, "\"$DEFAULT_HOST\" should stay whole")
        assertValueIsUnderItsLabel(SettingsUiTags.HOST_LABEL, SettingsUiTags.HOST_VALUE)
        assertValueFitsTheCard(SettingsUiTags.HOST_VALUE)
    }

    @Test
    fun givenConnected_whenLargeFont_thenTheShortValueSitsBesideItsLabel() {
        show(ConnectionState.Connected, fontScale = 2f)

        val label = bounds(SettingsUiTags.SHOT_STREAM_LABEL)
        val value = bounds(SettingsUiTags.SHOT_STREAM_VALUE)
        assertTrue(value.left >= label.right, "\"Connected\" ($value) should sit right of its label ($label)")
        assertTrue(value.top < label.bottom && value.bottom > label.top, "\"Connected\" should share the label's line")
        assertEquals(1, textLayout(SettingsUiTags.SHOT_STREAM_VALUE).lineCount)
        assertValueFitsTheCard(SettingsUiTags.SHOT_STREAM_VALUE)
    }

    /** Every line but the last ends at a space: no word, host or quoted token is split. */
    private fun assertBreaksOnlyAtSpaces(tag: String) {
        val layout = textLayout(tag)
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue(
                text[end - 1].isWhitespace() || text[end].isWhitespace(),
                "\"$text\" breaks mid-word after \"${text.substring(0, end)}\"",
            )
        }
    }

    private fun assertLineContains(
        tag: String,
        token: String,
    ) {
        val layout = textLayout(tag)
        val text = layout.layoutInput.text.text
        val lines = (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)) }
        assertTrue(lines.any { token in it }, "$token is split across the lines $lines")
    }

    /** A value too long to sit beside its label moves to its own line instead of squeezing in. */
    private fun assertValueIsUnderItsLabel(
        labelTag: String,
        valueTag: String,
    ) {
        val label = bounds(labelTag)
        val value = bounds(valueTag)
        assertTrue(value.top >= label.bottom, "the value ($value) should wrap under its label ($label)")
    }

    private fun assertValueFitsTheCard(tag: String) {
        val card = bounds(SettingsTestTags.CONNECTION)
        val value = bounds(tag)
        assertTrue(value.left >= card.left && value.right <= card.right, "the value ($value) leaves the card ($card)")
        // didOverflowWidth compares against the paragraph's layout width, not the text's, so a
        // short line reads as "overflowing"; check every line's ink fits the node instead.
        val layout = textLayout(tag)
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        assertTrue(widest <= layout.size.width, "a line ($widest px) runs past the value (${layout.size})")
        assertFalse(layout.didOverflowHeight, "the value is cut off")
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun textLayout(tag: String): TextLayoutResult =
        textLayout(composeRule.onNodeWithTag(tag, useUnmergedTree = true))

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
        /** The app's default Network host, which a tester off the Pi's network can't resolve. */
        const val DEFAULT_HOST = "raspberrypi.local:8080"

        val PHONE_WIDTH = 411.dp

        /** What a tester saw off a Pi network, with the default host. */
        val LONG_ERROR =
            ConnectionState.Error(
                "Unable to resolve host \"raspberrypi.local\": No address associated with hostname",
            )
    }
}
