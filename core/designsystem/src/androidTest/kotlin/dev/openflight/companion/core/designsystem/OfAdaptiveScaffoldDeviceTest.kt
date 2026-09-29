// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #55: at a 200 % system font scale the phone bottom bar's labels grew to fill their whole
 * slot, so neighbours touched and "Practice" and "Sessions" read as one word ("PracticeSessions").
 * Each label must stay on one line inside its own item with a visible gap to the next one, keep
 * its full text for TalkBack, and keep a 48 dp touch target, on the bottom bar and the tablet rail.
 * The font scale is forced through [LocalDensity], the phone width through a 360 dp box (a narrow
 * phone, narrower than any emulator here), so the result doesn't depend on the device.
 */
@RunWith(AndroidJUnit4::class)
class OfAdaptiveScaffoldDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        windowClass: OfWindowClass,
        fontScale: Float,
        labels: List<String> = APP_LABELS,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme {
                    val width = if (windowClass == OfWindowClass.COMPACT) Modifier.width(PHONE_WIDTH) else Modifier
                    Box(width.fillMaxHeight()) {
                        OfAdaptiveScaffold(
                            windowClass = windowClass,
                            items =
                                labels.mapIndexed { index, label ->
                                    OfNavigationItem(
                                        label = label,
                                        icon = OfIcons.Settings,
                                        selected = index == 0,
                                        onClick = {},
                                        testTag = tagOf(label),
                                    )
                                },
                        ) {
                            Box(Modifier)
                        }
                    }
                }
            }
        }
    }

    private fun tagOf(label: String) = "nav.$label"

    private fun px(dp: Float): Float = with(composeRule.density) { dp.dp.toPx() }

    private class Entry(
        val label: String,
        val item: Rect,
        val text: SemanticsNode,
    ) {
        val bounds: Rect get() = text.boundsInRoot

        val layout: TextLayoutResult
            get() {
                val layouts = mutableListOf<TextLayoutResult>()
                text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                return layouts.single()
            }
    }

    private fun entries(labels: List<String>): List<Entry> =
        labels.map { label ->
            val tag = tagOf(label)
            val item = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val text =
                composeRule
                    .onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .fetchSemanticsNode()
            Entry(label, item.boundsInRoot, text)
        }

    /** One line, inside its own item, the full name for TalkBack, and a 48 dp target. */
    private fun assertEachLabelFitsItsItem(labels: List<String>) {
        for (entry in entries(labels)) {
            assertEquals(1, entry.layout.lineCount, "\"${entry.label}\" wrapped")
            val b = entry.bounds
            val i = entry.item
            assertTrue(
                b.left >= i.left - EPSILON && b.right <= i.right + EPSILON,
                "\"${entry.label}\" ($b) spills out of its item ($i)",
            )
            assertTrue(i.width >= px(MIN_TOUCH_DP) - EPSILON && i.height >= px(MIN_TOUCH_DP) - EPSILON)
            // The item merges its label, so TalkBack reads the whole word even if it's ellipsized.
            composeRule.onNodeWithTag(tagOf(entry.label)).assert(hasText(entry.label))
        }
    }

    /** Neighbouring labels on the bottom bar keep a visible gap between them. */
    private fun assertBottomBarLabelsKeepAGap(labels: List<String>) {
        val sorted = entries(labels).sortedBy { it.bounds.left }
        for ((left, right) in sorted.zipWithNext()) {
            val gap = right.bounds.left - left.bounds.right
            assertTrue(
                gap >= px(MIN_LABEL_GAP_DP) - EPSILON,
                "\"${left.label}\" and \"${right.label}\" run together: gap ${gap}px < ${MIN_LABEL_GAP_DP}dp",
            )
        }
    }

    /**
     * Not ellipsized, and the drawn line fits the label's own width. (`hasVisualOverflow` isn't
     * used: for `softWrap = false` text it reports a width overflow even when nothing is cut.)
     */
    private fun assertShownInFull(
        entry: Entry,
        scale: String,
    ) {
        val layout = entry.layout
        assertFalse(layout.isLineEllipsized(0), "\"${entry.label}\" is ellipsized at $scale")
        assertTrue(
            layout.getLineRight(0) <= layout.size.width + EPSILON,
            "\"${entry.label}\" is cut off at $scale: ${layout.getLineRight(0)} > ${layout.size.width}",
        )
    }

    @Test
    fun given200PercentText_whenBottomBar_thenLabelsStayInsideTheirItemsWithAGap() {
        show(OfWindowClass.COMPACT, fontScale = 2f)

        assertEachLabelFitsItsItem(APP_LABELS)
        assertBottomBarLabelsKeepAGap(APP_LABELS)
    }

    @Test
    fun given200PercentText_whenBottomBar_thenTheAppLabelsAreNotTruncated() {
        show(OfWindowClass.COMPACT, fontScale = 2f)

        for (entry in entries(APP_LABELS)) {
            assertShownInFull(entry, "200 %")
        }
    }

    @Test
    fun given200PercentTextAndFiveTabs_whenBottomBar_thenLabelsStillKeepAGap() {
        // Plan F9 adds a fifth tab (Play); narrower slots may ellipsize but must never collide.
        val labels = listOf("Practice", "Play", "Sessions", "Bag", "Settings")
        show(OfWindowClass.COMPACT, fontScale = 2f, labels = labels)

        assertEachLabelFitsItsItem(labels)
        assertBottomBarLabelsKeepAGap(labels)
    }

    @Test
    fun givenDefaultText_whenBottomBar_thenLabelsShowInFull() {
        show(OfWindowClass.COMPACT, fontScale = 1f)

        assertEachLabelFitsItsItem(APP_LABELS)
        assertBottomBarLabelsKeepAGap(APP_LABELS)
        for (entry in entries(APP_LABELS)) {
            assertShownInFull(entry, "100 %")
        }
    }

    @Test
    fun given200PercentText_whenRail_thenLabelsStayInsideTheirItems() {
        show(OfWindowClass.EXPANDED, fontScale = 2f)

        assertEachLabelFitsItsItem(APP_LABELS)
        val sorted = entries(APP_LABELS).sortedBy { it.bounds.top }
        for ((above, below) in sorted.zipWithNext()) {
            assertTrue(below.bounds.top >= above.bounds.bottom - EPSILON, "rail labels overlap")
        }
    }

    private companion object {
        val APP_LABELS = listOf("Practice", "Sessions", "Bag", "Settings")
        val PHONE_WIDTH = 360.dp
        const val MIN_TOUCH_DP = 48f
        const val MIN_LABEL_GAP_DP = 4f
        const val EPSILON = 1f
    }
}
