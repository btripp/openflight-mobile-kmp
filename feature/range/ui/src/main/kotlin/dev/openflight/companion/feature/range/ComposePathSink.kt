// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush

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

/**
 * Plan F8a2p: a linear gradient over the shared stops, built once and laid from one point to
 * another per frame through its shader's local matrix, so moving it allocates nothing. Use one per
 * shape drawn in a frame: before API 29 a later matrix change would move a shader already recorded
 * into the frame's display list.
 */
internal class MovableLinearGradient(
    stops: List<RangeGradientStop>,
) {
    private val shader =
        LinearGradient(
            0f,
            0f,
            1f,
            0f,
            IntArray(stops.size) { stops[it].color.toArgb() },
            FloatArray(stops.size) { stops[it].offset },
            Shader.TileMode.CLAMP,
        )
    private val matrix = Matrix()
    private val values = FloatArray(MATRIX_VALUES)

    val brush: Brush = ShaderBrush(shader)

    /** Lays the gradient's offset 0 at ([startX], [startY]) and 1 at ([endX], [endY]), clamped beyond. */
    fun layOut(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        val dx = endX - startX
        val dy = endY - startY
        // Maps the unit gradient's (u, v) to start + u·(dx, dy) + v·(−dy, dx).
        values[Matrix.MSCALE_X] = dx
        values[Matrix.MSKEW_X] = -dy
        values[Matrix.MTRANS_X] = startX
        values[Matrix.MSKEW_Y] = dy
        values[Matrix.MSCALE_Y] = dx
        values[Matrix.MTRANS_Y] = startY
        values[Matrix.MPERSP_0] = 0f
        values[Matrix.MPERSP_1] = 0f
        values[Matrix.MPERSP_2] = 1f
        matrix.setValues(values)
        shader.setLocalMatrix(matrix)
    }

    private companion object {
        const val MATRIX_VALUES = 9
    }
}
