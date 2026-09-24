// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeTracerStyle
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.abs
import kotlin.math.max

/**
 * One trajectory projected onto one canvas size, computed once so a frame only interpolates.
 *
 * Sample `i` is the flight at `flightTime * i / segments` (the reference's tracer segments,
 * RangeSceneController.swift `configureTracer`). The ball rides the same samples, so it always sits
 * on the tip of the tracer. A sample behind the camera has NaN coordinates and is skipped.
 */
internal class FlightGeometry private constructor(
    val segments: Int,
) {
    private val count = segments + 1
    val xs = FloatArray(count)
    val ys = FloatArray(count)

    /** Tracer width at each sample: near → far width of [RangeTracerStyle], in pixels. */
    val tracerWidths = FloatArray(count)
    val ballRadii = FloatArray(count)
    val shadowXs = FloatArray(count)
    val shadowYs = FloatArray(count)
    val shadowRadiiX = FloatArray(count)
    val shadowRadiiY = FloatArray(count)

    /** The landing spot on the ground, for the landing marker. */
    var landing: Vec3 = Vec3.ZERO
        private set

    val sampleCount: Int get() = segments + 1

    /** Fractional sample index for playback [progress] in 0..1. */
    fun sampleAt(progress: Float): Float = progress.coerceIn(0f, 1f) * segments

    /** The tracer tip / ball centre at fractional sample [position]. */
    fun pointAt(position: Float): Offset = interpolate(xs, ys, position)

    fun shadowAt(position: Float): Offset = interpolate(shadowXs, shadowYs, position)

    fun valueAt(
        values: FloatArray,
        position: Float,
    ): Float {
        val index = position.toInt().coerceIn(0, segments)
        val next = (index + 1).coerceAtMost(segments)
        val fraction = position - index
        return values[index] + (values[next] - values[index]) * fraction
    }

    private fun interpolate(
        x: FloatArray,
        y: FloatArray,
        position: Float,
    ): Offset {
        val index = position.toInt().coerceIn(0, segments)
        val next = (index + 1).coerceAtMost(segments)
        val fraction = position - index
        return Offset(x[index] + (x[next] - x[index]) * fraction, y[index] + (y[next] - y[index]) * fraction)
    }

    private fun fill(
        trajectory: FlightTrajectory,
        projection: RangeProjection,
        style: RangeTracerStyle,
    ) {
        for (i in 0 until count) {
            val fraction = i.toDouble() / segments
            val point = trajectory.point(trajectory.flightTime * fraction)?.positionMeters ?: Vec3.ZERO
            val scene = RangeProjection.flightToScene(point)
            val depth = projection.depth(scene.x, scene.y, scene.z)
            val screen = projection.project(scene)
            xs[i] = if (screen.isSpecified) screen.x else Float.NaN
            ys[i] = if (screen.isSpecified) screen.y else Float.NaN
            val widthMeters = style.nearWidthMeters + fraction * (style.farWidthMeters - style.nearWidthMeters)
            tracerWidths[i] = max(projection.pixels(widthMeters, depth), MIN_TRACER_WIDTH_PIXELS)
            ballRadii[i] = max(projection.pixels(RangeProjection.BALL_RADIUS_METERS, depth), MIN_BALL_RADIUS_PIXELS)

            // RangeSceneController.swift: the shadow shrinks with height, down to 45 %.
            val radius = SHADOW_RADIUS_METERS * max(SHADOW_MIN_SCALE, 1 - scene.y / SHADOW_SHRINK_HEIGHT_METERS)
            val ground = projection.project(scene.x, SHADOW_HEIGHT_METERS, scene.z)
            val near = projection.project(scene.x, SHADOW_HEIGHT_METERS, scene.z + radius)
            val far = projection.project(scene.x, SHADOW_HEIGHT_METERS, scene.z - radius)
            shadowXs[i] = if (ground.isSpecified) ground.x else Float.NaN
            shadowYs[i] = if (ground.isSpecified) ground.y else Float.NaN
            shadowRadiiX[i] = projection.pixels(radius, projection.depth(scene.x, SHADOW_HEIGHT_METERS, scene.z))
            shadowRadiiY[i] = if (near.isSpecified && far.isSpecified) abs(near.y - far.y) / 2 else shadowRadiiX[i]
            if (i == segments) landing = Vec3(scene.x, 0.0, scene.z)
        }
    }

    companion object {
        private const val SHADOW_RADIUS_METERS = 0.38
        private const val SHADOW_HEIGHT_METERS = 0.025
        private const val SHADOW_SHRINK_HEIGHT_METERS = 85.0
        private const val SHADOW_MIN_SCALE = 0.45
        private const val MIN_TRACER_WIDTH_PIXELS = 3f
        private const val MIN_BALL_RADIUS_PIXELS = 3.5f

        fun build(
            trajectory: FlightTrajectory,
            projection: RangeProjection,
            segments: Int,
            style: RangeTracerStyle = RangeTracerStyle.highVisibility,
        ): FlightGeometry {
            val geometry = FlightGeometry(segments)
            geometry.fill(trajectory, projection, style)
            return geometry
        }
    }
}
