// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Tester report (build 5, largest text): measures this content at its full height and tells
 * [onOverflow] whether it's taller than [viewportHeight] px, the space its parent offers.
 *
 * The range's overlays only add `verticalScroll` when this says they overflow: a scroll container
 * over the scene, even a disabled one, swallows its pan, pinch and tap-to-select gestures. The
 * answer doesn't depend on whether the scroll is applied (it's always the full height against the
 * fixed viewport), so it can't flip back and forth. While not scrolling, the content is laid out at
 * most [viewportHeight] tall.
 */
internal fun Modifier.reportOverflow(
    viewportHeight: Int,
    onOverflow: (Boolean) -> Unit,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
        onOverflow(placeable.height > viewportHeight)
        val height =
            if (constraints.hasBoundedHeight) placeable.height.coerceAtMost(constraints.maxHeight) else placeable.height
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
