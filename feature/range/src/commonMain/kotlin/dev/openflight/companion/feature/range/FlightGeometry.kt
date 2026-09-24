// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeTracerStyle
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One trajectory's tracer samples: their scene-space positions, computed once per flight, and
 * their projection through the current camera, recomputed by [reproject] whenever the camera
 * moves (every frame under the follow camera, plan R7a) without allocating.
 *
 * Sample `i` is the flight at `flightTime * i / segments` (the reference's tracer segments,
 * RangeSceneController.swift `configureTracer`). The ball rides the same samples, so it always sits
 * on the tip of the tracer. A sample behind the camera has NaN screen coordinates; [projectBetween]
 * gives the point where the tracer crosses the near plane, so the drawn tracer stays one line.
 */
class FlightGeometry private constructor(
    val segments: Int,
    private val style: RangeTracerStyle,
) {
    private val count = segments + 1

    /** Scene-space sample positions (y includes the ball-radius lift, like [RangeProjection.flightToScene]). */
    val worldXs = DoubleArray(count)
    val worldYs = DoubleArray(count)
    val worldZs = DoubleArray(count)

    val xs = FloatArray(count)
    val ys = FloatArray(count)

    /** Each sample's distance in front of the camera. */
    val depths = DoubleArray(count)

    /** Tracer width at each sample: near → far width of [RangeTracerStyle], in pixels. */
    val tracerWidths = FloatArray(count)
    val ballRadii = FloatArray(count)
    val shadowXs = FloatArray(count)
    val shadowYs = FloatArray(count)
    val shadowRadiiX = FloatArray(count)
    val shadowRadiiY = FloatArray(count)

    private val scratch = FloatArray(2)
    private var projection: RangeProjection? = null

    /** The landing spot on the ground, for the landing marker. */
    var landing: Vec3 = Vec3.ZERO
        private set

    val sampleCount: Int get() = segments + 1

    /** Fractional sample index for playback [progress] in 0..1. */
    fun sampleAt(progress: Float): Float = progress.coerceIn(0f, 1f) * segments

    /** The tracer tip / ball centre at fractional sample [position]. */
    fun pointAt(position: Float): ScreenPoint = interpolate(xs, ys, position)

    fun shadowAt(position: Float): ScreenPoint = interpolate(shadowXs, shadowYs, position)

    fun valueAt(
        values: FloatArray,
        position: Float,
    ): Float {
        val index = position.toInt().coerceIn(0, segments)
        val next = (index + 1).coerceAtMost(segments)
        val fraction = position - index
        return values[index] + (values[next] - values[index]) * fraction
    }

    /** Whether sample [index] is in front of the camera's near plane. */
    fun isVisible(index: Int): Boolean = depths[index] > RangeProjection.NEAR_PLANE_METERS

    /**
     * Projects the scene point [fraction] of the way from sample [index] to the next one into
     * [out] (x at 0, y at 1) and returns whether it is drawable. Pass [NEAR_PLANE_CROSSING] to get
     * where the segment enters the view, for a segment with one end behind the camera.
     */
    fun projectBetween(
        index: Int,
        fraction: Double,
        out: FloatArray,
    ): Boolean {
        val projection = projection ?: return false
        val next = (index + 1).coerceAtMost(segments)
        val t =
            if (fraction == NEAR_PLANE_CROSSING) {
                val near = RangeProjection.NEAR_PLANE_METERS * 2
                val span = depths[next] - depths[index]
                if (span == 0.0) 0.0 else ((near - depths[index]) / span).coerceIn(0.0, 1.0)
            } else {
                fraction
            }
        return projection.projectInto(
            worldXs[index] + (worldXs[next] - worldXs[index]) * t,
            worldYs[index] + (worldYs[next] - worldYs[index]) * t,
            worldZs[index] + (worldZs[next] - worldZs[index]) * t,
            out,
            0,
        )
    }

    private fun interpolate(
        x: FloatArray,
        y: FloatArray,
        position: Float,
    ): ScreenPoint {
        val index = position.toInt().coerceIn(0, segments)
        val next = (index + 1).coerceAtMost(segments)
        val fraction = position - index
        return ScreenPoint(x[index] + (x[next] - x[index]) * fraction, y[index] + (y[next] - y[index]) * fraction)
    }

    private fun sample(trajectory: FlightTrajectory) {
        for (i in 0 until count) {
            val fraction = i.toDouble() / segments
            val point = trajectory.point(trajectory.flightTime * fraction)?.positionMeters ?: Vec3.ZERO
            val scene = RangeProjection.flightToScene(point)
            worldXs[i] = scene.x
            worldYs[i] = scene.y
            worldZs[i] = scene.z
            if (i == segments) landing = Vec3(scene.x, 0.0, scene.z)
        }
    }

    /** Re-projects every sample through [projection]'s current camera. Allocation-free. */
    fun reproject(projection: RangeProjection) {
        this.projection = projection
        val maxTracerWidth = projection.height * MAX_TRACER_WIDTH_FRACTION
        for (i in 0 until count) {
            val fraction = i.toDouble() / segments
            val x = worldXs[i]
            val y = worldYs[i]
            val z = worldZs[i]
            val depth = projection.depth(x, y, z)
            depths[i] = depth
            projection.projectInto(x, y, z, scratch, 0)
            xs[i] = scratch[0]
            ys[i] = scratch[1]
            val widthMeters = style.nearWidthMeters + fraction * (style.farWidthMeters - style.nearWidthMeters)
            tracerWidths[i] =
                if (depth > RangeProjection.NEAR_PLANE_METERS) {
                    min(max(projection.pixels(widthMeters, depth), MIN_TRACER_WIDTH_PIXELS), maxTracerWidth)
                } else {
                    maxTracerWidth
                }
            ballRadii[i] = max(projection.pixels(RangeProjection.BALL_RADIUS_METERS, depth), MIN_BALL_RADIUS_PIXELS)

            // RangeSceneController.swift: the shadow shrinks with height, down to 45 %.
            val radius = SHADOW_RADIUS_METERS * max(SHADOW_MIN_SCALE, 1 - y / SHADOW_SHRINK_HEIGHT_METERS)
            val groundDepth = projection.depth(x, SHADOW_HEIGHT_METERS, z)
            val onScreen = projection.projectInto(x, SHADOW_HEIGHT_METERS, z, scratch, 0)
            shadowXs[i] = scratch[0]
            shadowYs[i] = scratch[1]
            shadowRadiiX[i] = projection.pixels(radius, groundDepth)
            val nearY =
                if (projection.projectInto(
                        x,
                        SHADOW_HEIGHT_METERS,
                        z + radius,
                        scratch,
                        0,
                    )
                ) {
                    scratch[1]
                } else {
                    Float.NaN
                }
            val farY =
                if (projection.projectInto(
                        x,
                        SHADOW_HEIGHT_METERS,
                        z - radius,
                        scratch,
                        0,
                    )
                ) {
                    scratch[1]
                } else {
                    Float.NaN
                }
            shadowRadiiY[i] =
                if (onScreen && !nearY.isNaN() && !farY.isNaN()) abs(nearY - farY) / 2 else shadowRadiiX[i]
        }
    }

    companion object {
        /** A [projectBetween] fraction meaning "where the segment crosses the near plane". */
        const val NEAR_PLANE_CROSSING = -1.0

        private const val SHADOW_RADIUS_METERS = 0.38
        private const val SHADOW_HEIGHT_METERS = 0.025
        private const val SHADOW_SHRINK_HEIGHT_METERS = 85.0
        private const val SHADOW_MIN_SCALE = 0.45
        private const val MIN_TRACER_WIDTH_PIXELS = 3f
        private const val MIN_BALL_RADIUS_PIXELS = 3.5f

        /** A close follow camera would otherwise draw the tracer tens of pixels wide. */
        private const val MAX_TRACER_WIDTH_FRACTION = 0.012f

        fun build(
            trajectory: FlightTrajectory,
            projection: RangeProjection,
            segments: Int,
            style: RangeTracerStyle = RangeTracerStyle.highVisibility,
        ): FlightGeometry {
            val geometry = FlightGeometry(segments, style)
            geometry.sample(trajectory)
            geometry.reproject(projection)
            return geometry
        }
    }
}
