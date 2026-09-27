// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Plan F8a2p: where the UI overlaid on the range sits, so the shared scene keeps its yardage
 * labels and far markers clear of it ([RangeFrame.setObstructions]). Each overlaid element reports
 * its bounds through [rangeObstruction], the canvas its own through [canvasBounds], and [packed]
 * turns them into the canvas's pixels. An element that leaves the screen takes its rectangle with it.
 */
internal class RangeObstructionTracker {
    private val rects = mutableStateMapOf<String, Rect>()

    /** The range canvas's bounds in the root, the frame [packed] measures from. */
    var canvasBounds by mutableStateOf(Rect.Zero)

    fun put(
        key: String,
        bounds: Rect,
    ) {
        if (rects[key] != bounds) rects[key] = bounds
    }

    fun remove(key: String) {
        rects.remove(key)
    }

    /**
     * The rectangles in canvas pixels, `left, top, right, bottom` each, clipped to the canvas (a
     * snapshot read: a draw that calls it redraws when they change).
     */
    fun packed(): FloatArray {
        val canvas = canvasBounds
        val visible = rects.values.map { it.intersect(canvas) }.filter { it.width > 0f && it.height > 0f }
        val out = FloatArray(visible.size * RangeObstructions.RECT_STRIDE)
        visible.forEachIndexed { index, rect ->
            val at = index * RangeObstructions.RECT_STRIDE
            out[at + LEFT] = rect.left - canvas.left
            out[at + TOP] = rect.top - canvas.top
            out[at + RIGHT] = rect.right - canvas.left
            out[at + BOTTOM] = rect.bottom - canvas.top
        }
        return out
    }
}

// A packed rectangle's fields.
private const val LEFT = 0
private const val TOP = 1
private const val RIGHT = 2
private const val BOTTOM = 3

/** Plan F8a2p: reports this element's bounds to [tracker] under [key] while it's on screen. */
internal fun Modifier.rangeObstruction(
    key: String,
    tracker: RangeObstructionTracker?,
): Modifier = if (tracker == null) this else this then ObstructionElement(key, tracker)

private data class ObstructionElement(
    val key: String,
    val tracker: RangeObstructionTracker,
) : ModifierNodeElement<ObstructionNode>() {
    override fun create(): ObstructionNode = ObstructionNode(key, tracker)

    override fun update(node: ObstructionNode) {
        if (node.key != key || node.tracker !== tracker) {
            node.tracker.remove(node.key)
            node.key = key
            node.tracker = tracker
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "rangeObstruction"
        properties["key"] = key
    }
}

private class ObstructionNode(
    var key: String,
    var tracker: RangeObstructionTracker,
) : Modifier.Node(),
    GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        tracker.put(key, coordinates.boundsInRoot())
    }

    override fun onDetach() {
        tracker.remove(key)
    }
}
