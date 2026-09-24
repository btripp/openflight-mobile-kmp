// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.graphics.Path
import kotlin.math.sqrt

/**
 * The tracer as one filled ribbon (so its translucent colour never doubles up at joints), with
 * the per-sample perspective widths of [FlightGeometry]. Where the flight passes behind the camera
 * (the follow camera sits behind the ball, so the tee end usually does) the ribbon is cut at the
 * near plane rather than dropped, so the visible part stays one line running to the screen edge.
 *
 * [build] rewrites the same [path] and scratch arrays every frame; it allocates nothing.
 */
internal class TracerRibbon {
    val path = Path()

    /** The tracer's tip, where the ball is drawn; NaN when it is behind the camera. */
    var tipX = Float.NaN
        private set
    var tipY = Float.NaN
        private set

    private var runX = FloatArray(0)
    private var runY = FloatArray(0)
    private var runWidth = FloatArray(0)
    private var normalX = FloatArray(0)
    private var normalY = FloatArray(0)
    private var runLength = 0
    private val point = FloatArray(2)

    /** Sizes the scratch arrays for a flight of [segments] segments (plus the near-plane crossings). */
    fun ensureCapacity(segments: Int) {
        val capacity = segments + EXTRA_POINTS
        if (runX.size >= capacity) return
        runX = FloatArray(capacity)
        runY = FloatArray(capacity)
        runWidth = FloatArray(capacity)
        normalX = FloatArray(capacity)
        normalY = FloatArray(capacity)
    }

    /** The tracer from the tee up to [at], a fractional sample index of [geometry]. */
    fun build(
        geometry: FlightGeometry,
        at: Float,
    ) {
        path.rewind()
        runLength = 0
        val whole = at.toInt().coerceIn(0, geometry.segments)
        val fraction = (at - whole).toDouble()
        for (index in 0 until whole) visitSegment(geometry, index, 1.0)
        val between = whole < geometry.segments && fraction > 0.0
        if (between) visitSegment(geometry, whole, fraction)
        flush()

        val tipDepth =
            if (between) {
                geometry.depths[whole] + (geometry.depths[whole + 1] - geometry.depths[whole]) * fraction
            } else {
                geometry.depths[whole]
            }
        val tipVisible =
            when {
                tipDepth <= RangeProjection.NEAR_PLANE_METERS -> {
                    false
                }

                between -> {
                    geometry.projectBetween(whole, fraction, point)
                }

                else -> {
                    point[0] = geometry.xs[whole]
                    point[1] = geometry.ys[whole]
                    true
                }
            }
        tipX = if (tipVisible) point[0] else Float.NaN
        tipY = if (tipVisible) point[1] else Float.NaN
    }

    /** Adds sample [index] → [fraction] of the way to the next sample to the current run. */
    private fun visitSegment(
        geometry: FlightGeometry,
        index: Int,
        fraction: Double,
    ) {
        val next = index + 1
        val startVisible = geometry.isVisible(index)
        val endDepth = geometry.depths[index] + (geometry.depths[next] - geometry.depths[index]) * fraction
        val endVisible = endDepth > RangeProjection.NEAR_PLANE_METERS
        val endWidth =
            geometry.tracerWidths[index] +
                (geometry.tracerWidths[next] - geometry.tracerWidths[index]) * fraction.toFloat()
        if (startVisible && runLength == 0) add(geometry.xs[index], geometry.ys[index], geometry.tracerWidths[index])
        when {
            startVisible && endVisible -> {
                addEnd(geometry, index, fraction, endWidth)
            }

            startVisible -> {
                if (geometry.projectBetween(index, FlightGeometry.NEAR_PLANE_CROSSING, point)) {
                    add(point[0], point[1], geometry.tracerWidths[index])
                }
                flush()
            }

            endVisible -> {
                if (geometry.projectBetween(index, FlightGeometry.NEAR_PLANE_CROSSING, point)) {
                    add(point[0], point[1], endWidth)
                }
                addEnd(geometry, index, fraction, endWidth)
            }
        }
    }

    private fun addEnd(
        geometry: FlightGeometry,
        index: Int,
        fraction: Double,
        endWidth: Float,
    ) {
        if (fraction >= 1.0) {
            add(geometry.xs[index + 1], geometry.ys[index + 1], geometry.tracerWidths[index + 1])
        } else if (geometry.projectBetween(index, fraction, point)) {
            add(point[0], point[1], endWidth)
        }
    }

    private fun add(
        x: Float,
        y: Float,
        width: Float,
    ) {
        if (runLength >= runX.size) return
        runX[runLength] = x
        runY[runLength] = y
        runWidth[runLength] = width
        runLength++
    }

    /** Appends the current run to [path] as a closed ribbon: one edge out, the other edge back. */
    private fun flush() {
        val n = runLength
        runLength = 0
        if (n < 2) return
        var lastNormalX = 0f
        var lastNormalY = -1f
        for (k in 0 until n) {
            val previous = if (k == 0) 0 else k - 1
            val following = if (k == n - 1) n - 1 else k + 1
            val tangentX = runX[following] - runX[previous]
            val tangentY = runY[following] - runY[previous]
            val length = sqrt(tangentX * tangentX + tangentY * tangentY)
            if (length > MIN_TANGENT_PIXELS) {
                lastNormalX = -tangentY / length
                lastNormalY = tangentX / length
            }
            normalX[k] = lastNormalX
            normalY[k] = lastNormalY
        }
        for (k in 0 until n) {
            val half = runWidth[k] / 2
            val x = runX[k] + normalX[k] * half
            val y = runY[k] + normalY[k] * half
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in n - 1 downTo 0) {
            val half = runWidth[k] / 2
            path.lineTo(runX[k] - normalX[k] * half, runY[k] - normalY[k] * half)
        }
        path.close()
    }

    private companion object {
        const val MIN_TANGENT_PIXELS = 0.01f

        /** Room for the near-plane crossing points and the fractional tip. */
        const val EXTRA_POINTS = 3
    }
}
