// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import kotlin.math.sqrt

/**
 * The tracer as one filled ribbon (so its translucent colour never doubles up at joints), with
 * the per-sample perspective widths of [FlightGeometry]. Where the flight passes behind the camera
 * (the follow camera sits behind the ball, so the tee end usually does) the ribbon is cut at the
 * near plane rather than dropped, so the visible part stays one line running to the screen edge.
 * The platform fills [path] with [RangeVisualStyle.tracer] and draws the ball at ([tipX], [tipY]).
 *
 * Plan F8a2a: with a [glow] sink, the same run is also written there [glowWidthFactor] times as
 * wide; the platform fills it with [RangeVisualStyle.tracerGlow] under the core, the broadcast
 * look of a bright line in a soft halo.
 *
 * Plan F8a2t: with a [painter], each visible run (its points, widths, sample positions and
 * normals, [TracerRun]) goes to the painter instead of [path] and [glow], which then stay untouched;
 * [ShotTrail] paints its styles that way.
 *
 * [build] rewrites the same paths and scratch arrays every frame; it allocates nothing.
 */
class TracerRibbon<P : PathSink>(
    val path: P,
    val glow: P? = null,
    private val glowWidthFactor: Float = 1f,
    private val painter: TracerRunPainter? = null,
) {
    /** The tracer's tip, where the ball is drawn; NaN when it is behind the camera. */
    var tipX = Float.NaN
        private set
    var tipY = Float.NaN
        private set

    private val run = TracerRun()
    private val point = FloatArray(2)

    /** Sizes the scratch arrays for a flight of [segments] segments (plus the near-plane crossings). */
    fun ensureCapacity(segments: Int) {
        run.ensureCapacity(segments + EXTRA_POINTS)
    }

    /** The tracer from the tee up to [at], a fractional sample index of [geometry]. */
    fun build(
        geometry: FlightGeometry,
        at: Float,
    ) {
        if (painter == null) {
            path.rewind()
            glow?.rewind()
        }
        run.size = 0
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
        if (startVisible && run.size == 0) {
            run.add(geometry.xs[index], geometry.ys[index], geometry.tracerWidths[index], index.toFloat())
        }
        when {
            startVisible && endVisible -> {
                addEnd(geometry, index, fraction, endWidth)
            }

            startVisible -> {
                if (geometry.projectBetween(index, FlightGeometry.NEAR_PLANE_CROSSING, point)) {
                    run.add(point[0], point[1], geometry.tracerWidths[index], index + geometry.nearCrossing(index))
                }
                flush()
            }

            endVisible -> {
                if (geometry.projectBetween(index, FlightGeometry.NEAR_PLANE_CROSSING, point)) {
                    run.add(point[0], point[1], endWidth, index + geometry.nearCrossing(index))
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
            run.add(geometry.xs[index + 1], geometry.ys[index + 1], geometry.tracerWidths[index + 1], index + 1f)
        } else if (geometry.projectBetween(index, fraction, point)) {
            run.add(point[0], point[1], endWidth, index + fraction.toFloat())
        }
    }

    /** Hands the current run to the [painter], or appends it to [path] (and [glow]) as a closed ribbon. */
    private fun flush() {
        val n = run.size
        if (n < 2) {
            run.size = 0
            return
        }
        run.computeNormals()
        if (painter != null) {
            painter.paint(run)
        } else {
            run.writeRibbon(path, 0, n - 1, 1f)
            glow?.let { run.writeRibbon(it, 0, n - 1, glowWidthFactor) }
        }
        run.size = 0
    }

    private companion object {
        /** Room for the near-plane crossing points and the fractional tip. */
        const val EXTRA_POINTS = 3
    }
}

/** Plan F8a2t: receives each visible run of a [TracerRibbon] (see [TracerRibbon.build]). */
fun interface TracerRunPainter {
    fun paint(run: TracerRun)
}

/**
 * Plan F8a2t: one visible run of a tracer, in screen space: [size] points with their ribbon
 * [widths] (pixels), their fractional sample [positions] in the flight ([FlightGeometry]'s index,
 * so proportional to time), and unit [normalXs]/[normalYs] across the ribbon. Reused (rewritten in
 * place) for every run and frame.
 */
class TracerRun {
    var xs = FloatArray(0)
        private set
    var ys = FloatArray(0)
        private set
    var widths = FloatArray(0)
        private set
    var positions = FloatArray(0)
        private set
    var normalXs = FloatArray(0)
        private set
    var normalYs = FloatArray(0)
        private set

    /** Each point's width multiplier for [writeRibbon] (a taper, a twist); 1 unless a painter sets it. */
    var widthFactors = FloatArray(0)
        private set
    var size = 0

    val capacity: Int get() = xs.size

    fun ensureCapacity(capacity: Int) {
        if (xs.size >= capacity) return
        xs = FloatArray(capacity)
        ys = FloatArray(capacity)
        widths = FloatArray(capacity)
        positions = FloatArray(capacity)
        normalXs = FloatArray(capacity)
        normalYs = FloatArray(capacity)
        widthFactors = FloatArray(capacity) { 1f }
    }

    /** Appends a point (ignored once full); its width factor starts at 1. */
    fun add(
        x: Float,
        y: Float,
        width: Float,
        position: Float,
    ) {
        if (size >= xs.size) return
        xs[size] = x
        ys[size] = y
        widths[size] = width
        positions[size] = position
        widthFactors[size] = 1f
        size++
    }

    /** Each point's unit normal from its neighbours' tangent (the last good one where points coincide). */
    fun computeNormals() {
        val n = size
        var lastNormalX = 0f
        var lastNormalY = -1f
        for (k in 0 until n) {
            val previous = if (k == 0) 0 else k - 1
            val following = if (k == n - 1) n - 1 else k + 1
            val tangentX = xs[following] - xs[previous]
            val tangentY = ys[following] - ys[previous]
            val length = sqrt(tangentX * tangentX + tangentY * tangentY)
            if (length > MIN_TANGENT_PIXELS) {
                lastNormalX = -tangentY / length
                lastNormalY = tangentX / length
            }
            normalXs[k] = lastNormalX
            normalYs[k] = lastNormalY
        }
    }

    /**
     * Points [from]..[to] (inclusive) as one closed ribbon in [sink]: one edge out, the other back,
     * [widthFactor] × each point's width × its [widthFactors]. Nothing for fewer than two points.
     */
    fun writeRibbon(
        sink: PathSink,
        from: Int,
        to: Int,
        widthFactor: Float,
    ) {
        if (to <= from) return
        for (k in from..to) {
            val half = widths[k] * widthFactor * widthFactors[k] / 2
            val x = xs[k] + normalXs[k] * half
            val y = ys[k] + normalYs[k] * half
            if (k == from) sink.moveTo(x, y) else sink.lineTo(x, y)
        }
        for (k in to downTo from) {
            val half = widths[k] * widthFactor * widthFactors[k] / 2
            sink.lineTo(xs[k] - normalXs[k] * half, ys[k] - normalYs[k] * half)
        }
        sink.close()
    }

    private companion object {
        const val MIN_TANGENT_PIXELS = 0.01f
    }
}
