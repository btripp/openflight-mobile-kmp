// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeTracerStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Plan F8f: a small still of one [ShotTrailStyle] for the range quick settings' style picker. The
 * shared [ShotTrail] writes it, from [ShotTrailPreview]'s landed 7-iron under the Settings preview's
 * three-quarter view, on a virtual [CANVAS_WIDTH] × [CANVAS_HEIGHT] canvas, so every swatch is the
 * same geometry both platforms draw on the range.
 *
 * The platform fills [background], then, scaled by [fit] into its swatch, every visible layer of
 * [trail] (like the range's own trail) and the ball at ([ballX], [ballY]). Build it once per style
 * and theme, off the main thread: the first one simulates the preview flights.
 */
class ShotTrailSwatch<P : PathSink>(
    val style: ShotTrailStyle,
    theme: RangeTheme,
    newPath: () -> P,
) {
    /** The trail's layers, drawn as on the range (the earlier-trail and landing layers stay hidden). */
    val trail: ShotTrail<P> = ShotTrail(theme.style, newPath)

    /** The swatch's backdrop: the theme's ground. */
    val background: RangeColor = theme.style.ground

    /** The arc's (and its ground line's) bounds on the virtual canvas, in pixels. */
    val minX: Float
    val minY: Float
    val maxX: Float
    val maxY: Float

    val ballX: Float
    val ballY: Float
    val ballRadius: Float

    init {
        val flight = ShotTrailPreview.flight(playbackId = 0)
        val pose = ShotTrailPreview.VIEW.applyTo(RangeCameraPlanner().pose)
        val projection = RangeProjection(pose, CANVAS_WIDTH, CANVAS_HEIGHT)
        val geometry = FlightGeometry.build(flight.trajectory, projection, SEGMENTS, SWATCH_TRACER)
        trail.ensureCapacity(SEGMENTS)
        trail.setOptions(style, LandingEffect.OFF, staticEffects = true)
        trail.setFlight(flight.spinRpm, flight.clubColorIndex)
        val tip = geometry.sampleAt(1f)
        trail.build(geometry, tip)

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in 0 until geometry.sampleCount) {
            if (!geometry.isVisible(index)) continue
            val pad = geometry.tracerWidths[index]
            left = min(left, geometry.xs[index] - pad)
            right = max(right, geometry.xs[index] + pad)
            top = min(top, geometry.ys[index] - pad)
            bottom = max(bottom, geometry.ys[index] + pad)
            val shadowY = geometry.shadowYs[index]
            if (!shadowY.isNaN()) bottom = max(bottom, shadowY)
        }
        minX = left
        minY = top
        maxX = right
        maxY = bottom
        ballX = geometry.valueAt(geometry.xs, tip)
        ballY = geometry.valueAt(geometry.ys, tip)
        ballRadius = geometry.valueAt(geometry.ballRadii, tip)
    }

    /**
     * How to draw the virtual canvas into a [width] × [height] swatch with [padding] on every side:
     * `[scale, offsetX, offsetY]`, so a point p lands at `p * scale + offset`, the arc centred.
     */
    fun fit(
        width: Float,
        height: Float,
        padding: Float,
    ): FloatArray {
        val spanX = max(maxX - minX, 1f)
        val spanY = max(maxY - minY, 1f)
        val scale = min((width - 2 * padding) / spanX, (height - 2 * padding) / spanY)
        val offsetX = (width - spanX * scale) / 2 - minX * scale
        val offsetY = (height - spanY * scale) / 2 - minY * scale
        return floatArrayOf(scale, offsetX, offsetY)
    }

    companion object {
        /** The virtual canvas: about a swatch's size, since the tracer's widths are in pixels. */
        const val CANVAS_WIDTH = 120f
        const val CANVAS_HEIGHT = 68f

        /** Fewer segments than the range's tracer: it's a thumbnail. */
        const val SEGMENTS = 48

        /** The range's tracer, [TRACER_BOOST] times as wide, so each style reads at thumbnail size. */
        private val SWATCH_TRACER: RangeTracerStyle =
            RangeTracerStyle.highVisibility.let {
                it.copy(
                    nearWidthMeters = it.nearWidthMeters * TRACER_BOOST,
                    farWidthMeters =
                        it.farWidthMeters * TRACER_BOOST,
                )
            }
        private const val TRACER_BOOST = 8.0
    }
}
