// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** The shared geometry's [PathSink] (plan F8c1), backed by one retained Compose [Path]. */
internal class ComposePathSink : PathSink {
    val path = Path()

    override fun rewind() = path.rewind()

    override fun moveTo(
        x: Float,
        y: Float,
    ) = path.moveTo(x, y)

    override fun lineTo(
        x: Float,
        y: Float,
    ) = path.lineTo(x, y)

    override fun close() = path.close()
}

/** A shared [RangeColor] as a Compose sRGB [Color] (a value class: nothing is allocated). */
internal fun RangeColor.toColor(): Color = Color(red = red, green = green, blue = blue, alpha = alpha)

/**
 * Plan F8a2a: a radial gradient over the stops (0 = centre, 1 = edge) on a circle of radius 1 at
 * the origin; the renderer translates and scales it onto the sun. Built once per style.
 */
@Suppress("SpreadOperator") // Brush takes the stops as varargs; the one copy happens at construction.
internal fun List<RangeGradientStop>.toUnitRadialBrush(): Brush =
    Brush.radialGradient(*map { it.offset to it.color.toColor() }.toTypedArray(), center = Offset.Zero, radius = 1f)

/** A vertical gradient over the stops, from [startY] to [endY]. Built once per style, never per frame. */
@Suppress("SpreadOperator") // Brush takes the stops as varargs; the one copy happens at construction.
internal fun List<RangeGradientStop>.toVerticalBrush(
    startY: Float = 0f,
    endY: Float = Float.POSITIVE_INFINITY,
): Brush = Brush.verticalGradient(*map { it.offset to it.color.toColor() }.toTypedArray(), startY = startY, endY = endY)
