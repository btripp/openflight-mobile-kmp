// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #65: a long status that wraps kept the fully rounded pill shape, so at 200% text its first
 * and last lines ran into the curved left and right edges. The inset is measured against the pill
 * as drawn (its fill and border, read back from a screenshot of the node), not against its layout
 * bounds, because the bounds are a rectangle and the problem is the curve inside them.
 */
@RunWith(AndroidJUnit4::class)
class OfPillDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var label by mutableStateOf("")

    private fun show(
        text: String,
        fontScale: Float,
    ) {
        label = text
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme {
                    // A plain, known backdrop so the pill's edge is the first pixel that differs from it.
                    Box(modifier = Modifier.background(BACKDROP).padding(OfSpacing.Md)) {
                        OfPill(
                            label = label,
                            tone = StatusTone.InProgress,
                            modifier = Modifier.width(PHONE_CONTENT_WIDTH).testTag(PILL),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun givenALongStatusAt200PercentText_whenShown_thenEveryLineKeepsClearOfThePillsEdges() {
        show(LONG_STATUS, fontScale = 2f)

        assertTrue(textLayout(LONG_STATUS).lineCount > 1, "the status should wrap at 200% text")
        assertLinesKeepClearOfTheEdges(LONG_STATUS)
    }

    @Test
    fun givenALongStatus_whenShown_thenEveryLineKeepsClearOfThePillsEdges() {
        show(LONG_STATUS, fontScale = 1f)

        assertTrue(textLayout(LONG_STATUS).lineCount > 1, "the status should wrap at 100% text")
        assertLinesKeepClearOfTheEdges(LONG_STATUS)
    }

    @Test
    fun givenAShortStatus_whenItGrowsToWrap_thenItsLinesKeepClearOfThePillsEdges() {
        show(SHORT_STATUS, fontScale = 2f)
        assertEquals(1, textLayout(SHORT_STATUS).lineCount)

        label = LONG_STATUS
        composeRule.waitForIdle()

        assertLinesKeepClearOfTheEdges(LONG_STATUS)
    }

    @Test
    fun givenAShortStatus_whenShown_thenItKeepsItsFullyRoundedEnds() {
        show(SHORT_STATUS, fontScale = 2f)

        val pill = composeRule.onNodeWithTag(PILL)
        val image = pill.captureToImage()
        val height = image.height
        // A fully rounded end is a semicircle: at the vertical middle the edge touches the bounds,
        // and a quarter of the height from the top it is already well inside them.
        val middle = edges(image, height / 2)
        val quarter = edges(image, height / 4)
        assertTrue(middle.first <= EDGE_TOLERANCE_PX, "the left end should reach the bounds at its middle")
        val expectedInset = height / 2f - kotlin.math.sqrt(height / 2f * height / 2f - height / 4f * height / 4f)
        assertTrue(
            abs(quarter.first - expectedInset) <= EDGE_TOLERANCE_PX + 1,
            "the left end isn't a semicircle: ${quarter.first} px in at a quarter height, expected $expectedInset",
        )
    }

    /**
     * For the first and last lines, at the middle of each, the text stays at least [MIN_INSET] clear
     * of the pill's visible left and right edges.
     */
    private fun assertLinesKeepClearOfTheEdges(text: String) {
        val pill = composeRule.onNodeWithTag(PILL)
        val pillBounds = pill.fetchSemanticsNode().boundsInRoot
        val textNode = composeRule.onNode(hasText(text), useUnmergedTree = true)
        val textBounds: Rect = textNode.fetchSemanticsNode().boundsInRoot
        val layout = textLayout(text)
        val image = pill.captureToImage()
        val minInset = with(composeRule.density) { MIN_INSET.toPx() }

        for (line in listOf(0, layout.lineCount - 1).distinct()) {
            val middle = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val row = (textBounds.top + middle - pillBounds.top).toInt()
            val (edgeLeft, edgeRight) = edges(image, row)
            val lineLeft = textBounds.left + layout.getLineLeft(line) - pillBounds.left
            val lineRight = textBounds.left + layout.getLineRight(line) - pillBounds.left
            assertTrue(
                lineLeft - edgeLeft >= minInset,
                "line $line starts ${lineLeft - edgeLeft} px from the pill's left edge (min $minInset)",
            )
            assertTrue(
                edgeRight - lineRight >= minInset,
                "line $line ends ${edgeRight - lineRight} px from the pill's right edge (min $minInset)",
            )
        }
    }

    /** The first and last pixel in [row] that isn't the backdrop: the pill's visible edges there. */
    private fun edges(
        image: ImageBitmap,
        row: Int,
    ): Pair<Int, Int> {
        val pixels = image.toPixelMap()
        val y = row.coerceIn(0, image.height - 1)

        fun isPill(x: Int): Boolean {
            val c = pixels[x, y]
            return abs(c.red - BACKDROP.red) + abs(c.green - BACKDROP.green) + abs(c.blue - BACKDROP.blue) > COLOR_DELTA
        }
        val left = (0 until image.width).first { isPill(it) }
        val right = (image.width - 1 downTo 0).first { isPill(it) }
        return left to right
    }

    private fun textLayout(text: String): TextLayoutResult =
        textLayout(composeRule.onNode(hasText(text), useUnmergedTree = true))

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
        const val PILL = "pill"
        val BACKDROP = Color.Black
        const val COLOR_DELTA = 0.03f
        const val EDGE_TOLERANCE_PX = 2
        val MIN_INSET = 12.dp

        /** A phone's width inside the Settings connection card, as in issue #65's screenshot. */
        val PHONE_CONTENT_WIDTH = 340.dp
        const val SHORT_STATUS = "Connected"
        const val LONG_STATUS =
            "Reconnecting: Unable to resolve host \"raspberrypi.local\": No address associated with hostname"
    }
}
