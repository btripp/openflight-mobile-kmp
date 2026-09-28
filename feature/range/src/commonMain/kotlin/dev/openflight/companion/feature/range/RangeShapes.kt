// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plan F8a2a: where a shape's haze is measured from (a point on the ground, in scene metres) and
 * how strongly it hazes: 1 for trees, less for the yardage targets so they stay readable.
 */
class HazeAnchor(
    val x: Double,
    val z: Double,
    val strength: Float = 1f,
) {
    /**
     * The shape's [base] colour seen through [projection]'s camera, packed `0xAARRGGBB`. Plan
     * F8a2p: hazed by [RangeProjection.hazeDistance], the same distance the ground's haze overlay
     * uses, so a tree fades exactly as much as the grass at its foot.
     */
    fun argb(
        base: RangeColor,
        haze: RangeHaze,
        projection: RangeProjection,
    ): Int = haze.argb(base, projection.hazeDistance(x, z), strength)
}

/**
 * A flat shape of the scene in world (scene) space, with the one [path] it is drawn with: one or
 * more rings (subpaths), each a simple outline. [project] rewrites that path for the current
 * camera, clipping every ring at the near plane (Sutherland–Hodgman against one plane, at twice
 * [RangeProjection.NEAR_PLANE_METERS]) so parts behind the camera don't wrap around. It allocates
 * nothing: the clip buffers are the scene's shared scratch arrays. The platform fills [path] with
 * [argb] while [visible]; several rings in one path (plan F8a2a: the grass mottling, the trees'
 * contact shadows) cost one fill, and where they overlap they don't darken twice.
 *
 * Plan F8a2a: with a [haze] anchor, [tint] re-colours it for each camera pose, [color] hazed by
 * the anchor's distance from the camera; without one it's always [color].
 */
class WorldPolygon<P : PathSink>(
    /** x, y, z triples, ring after ring. */
    val vertices: DoubleArray,
    /** Its colour up close, before any haze. */
    val color: RangeColor,
    val path: P,
    /** How many vertices each ring has; one ring of every vertex by default. */
    private val ringSizes: IntArray = intArrayOf(vertices.size / STRIDE),
    private val haze: HazeAnchor? = null,
) {
    /** False when nothing of it is in front of the camera; [path] is empty then. */
    var visible = false
        private set

    /** The colour to fill with for the last [tint]ed pose, `0xAARRGGBB`. */
    var argb: Int = color.toArgb()
        private set

    /** Plan F8a2p: the drawn outline's pixel bounds at the last [project] (meaningless while not [visible]). */
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set

    /** Re-colours it for [projection]'s camera; without a haze anchor it keeps [color]. Allocation-free. */
    fun tint(
        style: RangeHaze,
        projection: RangeProjection,
    ) {
        argb = haze?.argb(color, style, projection) ?: color.toArgb()
    }

    /** Plan F8a2p: multiplies the tinted colour's alpha by [factor] (a marker behind the overlay UI). */
    fun fade(factor: Float) {
        val alpha = ((argb ushr ALPHA_SHIFT) * factor.coerceIn(0f, 1f) + HALF_STEP).toInt()
        argb = (argb and RGB_MASK) or (alpha shl ALPHA_SHIFT)
    }

    val vertexCount: Int get() = vertices.size / STRIDE

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        path.rewind()
        visible = false
        left = Float.POSITIVE_INFINITY
        top = Float.POSITIVE_INFINITY
        right = Float.NEGATIVE_INFINITY
        bottom = Float.NEGATIVE_INFINITY
        var start = 0
        for (ring in ringSizes.indices) {
            val size = ringSizes[ring]
            if (projectRing(projection, scratch, start, size)) visible = true
            start += size
        }
    }

    /** Clips and projects the ring of [size] vertices from vertex [start]; returns whether any of it was drawn. */
    private fun projectRing(
        projection: RangeProjection,
        scratch: SceneScratch,
        start: Int,
        size: Int,
    ): Boolean {
        val near = RangeProjection.NEAR_PLANE_METERS * 2
        var clipped = 0
        for (offset in 0 until size) {
            val index = start + offset
            val next = start + (offset + 1) % size
            val cx = vertices[index * STRIDE]
            val cy = vertices[index * STRIDE + Y]
            val cz = vertices[index * STRIDE + Z]
            val nx = vertices[next * STRIDE]
            val ny = vertices[next * STRIDE + Y]
            val nz = vertices[next * STRIDE + Z]
            val currentDepth = projection.depth(cx, cy, cz)
            val nextDepth = projection.depth(nx, ny, nz)
            if (currentDepth >= near) {
                clipped = scratch.put(clipped, cx, cy, cz)
            }
            if ((currentDepth >= near) != (nextDepth >= near)) {
                val t = (near - currentDepth) / (nextDepth - currentDepth)
                clipped = scratch.put(clipped, cx + (nx - cx) * t, cy + (ny - cy) * t, cz + (nz - cz) * t)
            }
        }
        if (clipped < MIN_POLYGON_VERTICES) return false
        val point = scratch.point
        for (index in 0 until clipped) {
            projection.projectInto(scratch.xs[index], scratch.ys[index], scratch.zs[index], point, 0)
            val x = point[0]
            val y = point[1]
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        path.close()
        return true
    }
}

/**
 * Plan F8a2p: one soft mowing stripe, a band across the fairway [nearMeters]..[farMeters] down the
 * range. [polygon] is its outline (clipped to the fairway); the platform fills it with the
 * style's `stripeGradient` laid from ([startX], [startY]), its near edge, to ([endX], [endY]), its
 * far edge, so the stripe fades in and out along the range instead of ending at a hard line.
 *
 * The gradient runs square to the stripe's near edge as drawn (so that whole edge is clear), and
 * when the camera is over the stripe, from where its middle line comes into view, extended back to
 * where the near edge would be. [gradientVisible] is false when there's no gradient to draw (the
 * stripe is behind the camera or seen edge-on).
 */
class WorldStripe<P : PathSink>(
    val polygon: WorldPolygon<P>,
    private val centerX: Double,
    private val nearMeters: Double,
    private val farMeters: Double,
) {
    var startX = 0f
        private set
    var startY = 0f
        private set
    var endX = 0f
        private set
    var endY = 0f
        private set
    var gradientVisible = false
        private set

    /** Projects the outline and lays out its gradient for [projection]. Allocation-free. */
    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        polygon.project(projection, scratch)
        gradientVisible = polygon.visible && layOutGradient(projection, scratch.point)
    }

    @Suppress("ReturnCount") // Each early return is a way the stripe has no gradient to draw.
    private fun layOutGradient(
        projection: RangeProjection,
        point: FloatArray,
    ): Boolean {
        // Scene z of the near and far edges (downrange is −z), and where the middle line enters view.
        val nearZ = -nearMeters
        val farZ = -farMeters
        val minDepth = RangeProjection.NEAR_PLANE_METERS * 2
        val nearDepth = projection.depth(centerX, 0.0, nearZ)
        val farDepth = projection.depth(centerX, 0.0, farZ)
        if (nearDepth < minDepth && farDepth < minDepth) return false
        // The fraction along the stripe (0 near, 1 far) of the first point in front of the camera.
        val from =
            when {
                nearDepth >= minDepth -> 0.0
                else -> (minDepth - nearDepth) / (farDepth - nearDepth)
            }
        val to =
            when {
                farDepth >= minDepth -> 1.0
                else -> (minDepth - nearDepth) / (farDepth - nearDepth)
            }
        if (to - from < MIN_VISIBLE_FRACTION) return false
        val fromZ = nearZ + (farZ - nearZ) * from
        val toZ = nearZ + (farZ - nearZ) * to
        // The near edge's direction on screen, from a metre either side of the middle line.
        projection.projectInto(centerX - 1.0, 0.0, fromZ, point, 0)
        val edgeX0 = point[0]
        val edgeY0 = point[1]
        projection.projectInto(centerX + 1.0, 0.0, fromZ, point, 0)
        var normalX = -(point[1] - edgeY0)
        var normalY = point[0] - edgeX0
        val normalLength = sqrt(normalX * normalX + normalY * normalY)
        if (normalLength < MIN_PIXELS || normalLength.isNaN()) return false
        normalX /= normalLength
        normalY /= normalLength
        projection.projectInto(centerX, 0.0, fromZ, point, 0)
        val fromX = point[0]
        val fromY = point[1]
        projection.projectInto(centerX, 0.0, toZ, point, 0)
        val along = (point[0] - fromX) * normalX + (point[1] - fromY) * normalY
        if (abs(along) < MIN_PIXELS) return false
        // Screen distance per unit of stripe fraction, then extend to fractions 0 and 1.
        val perUnit = along / (to - from).toFloat()
        startX = fromX - normalX * perUnit * from.toFloat()
        startY = fromY - normalY * perUnit * from.toFloat()
        endX = fromX + normalX * perUnit * (1 - from).toFloat()
        endY = fromY + normalY * perUnit * (1 - from).toFloat()
        return true
    }

    private companion object {
        const val MIN_VISIBLE_FRACTION = 1e-3
        const val MIN_PIXELS = 0.5f
    }
}

/**
 * Plan F8a2p: the screen rectangles the overlaid UI covers (the metric cards, the controls, the
 * chips), in canvas pixels, which yardage labels and far markers keep clear of. Each is `left, top,
 * right, bottom` in [rects]. Pure and allocation-free to query; [set] copies only when they changed.
 */
class RangeObstructions {
    private var rects = FloatArray(0)

    /** How many rectangles there are. */
    val count: Int get() = rects.size / RECT_STRIDE

    /** Replaces the rectangles with [packed] (`left, top, right, bottom` each); returns whether they changed. */
    fun set(packed: FloatArray): Boolean {
        if (packed.contentEquals(rects)) return false
        rects = packed.copyOf(packed.size - packed.size % RECT_STRIDE)
        return true
    }

    /** Whether the box [left]..[right] × [top]..[bottom] overlaps any rectangle (touching edges don't). */
    fun intersects(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Boolean {
        var at = 0
        while (at < rects.size) {
            val acrossOverlaps = left < rects[at + RIGHT] && right > rects[at]
            if (acrossOverlaps && top < rects[at + BOTTOM] && bottom > rects[at + TOP]) return true
            at += RECT_STRIDE
        }
        return false
    }

    companion object {
        const val RECT_STRIDE = 4
        private const val TOP = 1
        private const val RIGHT = 2
        private const val BOTTOM = 3
    }
}

/**
 * One tone of a tree crown (plan F8a2a): a few overlapping lobes, spheres seen as discs, written as
 * polygons into one [path] so both platforms fill exactly the same outline. The platform fills
 * [path] with [argb] while [visible]: [color] hazed by the tree's distance from the camera.
 */
class WorldCrown<P : PathSink>(
    /** x, y, z, radius per lobe. */
    val lobes: DoubleArray,
    /** Its colour up close, before any haze. */
    val color: RangeColor,
    val path: P,
) {
    /** Whether any lobe is in front of the camera; [path] is empty otherwise. */
    var visible = false
        private set

    /** The colour to fill with for the last tinted pose, `0xAARRGGBB`. */
    var argb: Int = color.toArgb()
        internal set

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        path.rewind()
        visible = false
        var lobe = 0
        while (lobe < lobes.size) {
            val x = lobes[lobe]
            val y = lobes[lobe + Y]
            val z = lobes[lobe + Z]
            val radius = lobes[lobe + RADIUS]
            lobe += LOBE_STRIDE
            val depth = projection.depth(x, y, z)
            if (depth <= RangeProjection.NEAR_PLANE_METERS + radius) continue
            projection.projectInto(x, y, z, scratch.point, 0)
            val centerX = scratch.point[0]
            val centerY = scratch.point[1]
            val pixels = projection.pixels(radius, depth)
            for (segment in 0 until LOBE_SEGMENTS) {
                val px = centerX + pixels * LOBE_COS[segment]
                val py = centerY + pixels * LOBE_SIN[segment]
                if (segment == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            visible = true
        }
    }

    companion object {
        /** Each lobe is x, y, z and its radius. */
        const val LOBE_STRIDE = 4
        private const val RADIUS = 3
    }
}

/**
 * A tree (plan F8a2a): a trunk and three crown tones, [crowns] dark underside, body and sunlit top,
 * drawn together (trunk, then each crown) in back-to-front order. Its contact shadow is part of the
 * scene's ground polygons.
 */
class WorldTree<P : PathSink>(
    val trunk: WorldPolygon<P>,
    val crowns: List<WorldCrown<P>>,
    private val x: Double,
    private val z: Double,
) {
    var depth = 0.0
        private set

    private val anchor = HazeAnchor(x, z)

    /** Plan F8a2a: re-colours the trunk and crowns for [projection]'s camera. */
    fun tint(
        haze: RangeHaze,
        projection: RangeProjection,
    ) {
        trunk.tint(haze, projection)
        for (index in crowns.indices) {
            val crown = crowns[index]
            crown.argb = anchor.argb(crown.color, haze, projection)
        }
    }

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        depth = projection.depth(x, 0.0, z)
        trunk.project(projection, scratch)
        for (index in crowns.indices) crowns[index].project(projection, scratch)
    }
}

/**
 * A yardage label anchored above a marker; [scale] is the pixels one metre spans there. The
 * platform lays out [text] at [RangeVisualStyle.maxLabelSize], bottom-centres it on ([anchorX],
 * [anchorY]) and scales it about that anchor to [fontPixels].
 */
class WorldLabel(
    val text: String,
    private val x: Double,
    private val y: Double,
    private val z: Double,
) {
    var anchorX = 0f
        private set
    var anchorY = 0f
        private set
    var scale = 0f
        private set

    /** Far labels bunch up at the horizon, so only readable (near enough) ones are drawn. */
    var visible = false
        private set

    /**
     * Plan F8a2p: the label's box would overlap the overlaid UI ([RangeObstructions]), so it isn't
     * drawn. Every yardage it names is also a marker on the range and the carry is in the metrics,
     * so hiding it hides no information that isn't on screen elsewhere.
     */
    var obstructed = false
        private set

    /** Whether the platform draws it: [visible] and not [obstructed]. */
    val drawn: Boolean get() = visible && !obstructed

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        obstructed = false
        val depth = projection.depth(x, y, z)
        visible = depth > RangeProjection.NEAR_PLANE_METERS
        if (!visible) return
        projection.projectInto(x, y, z, scratch.point, 0)
        anchorX = scratch.point[0]
        anchorY = scratch.point[1]
        scale = projection.pixels(1.0, depth)
        visible = scale >= projection.height * MIN_LABEL_PIXELS_PER_METER_FRACTION
    }

    /**
     * The label's font size in pixels: [heightMeters] in the world at its depth, clamped to
     * [minPixels]..[maxPixels] (the style's `labelHeightMeters`, `minLabelSize`, `maxLabelSize`).
     */
    fun fontPixels(
        heightMeters: Float,
        minPixels: Float,
        maxPixels: Float,
    ): Float = (scale * heightMeters).coerceIn(minPixels, maxPixels)

    /**
     * Plan F8a2p: marks it [obstructed] when its box at [fontPixels] overlaps [obstructions]. The
     * box is estimated the same way on both platforms (bold digits are about [GLYPH_WIDTH_EM] of the
     * font size wide, the line [LINE_HEIGHT_EM] tall), bottom-centred on the anchor like the text.
     */
    fun obstruct(
        obstructions: RangeObstructions,
        fontPixels: Float,
    ) {
        obstructed = false
        if (!visible || obstructions.count == 0) return
        val halfWidth = fontPixels * (GLYPH_WIDTH_EM * text.length + PADDING_EM) / 2
        obstructed =
            obstructions.intersects(
                anchorX - halfWidth,
                anchorY - fontPixels * LINE_HEIGHT_EM,
                anchorX + halfWidth,
                anchorY,
            )
    }

    companion object {
        /** A bold digit's advance, in ems. */
        const val GLYPH_WIDTH_EM = 0.62f

        /** A label line's height, in ems. */
        const val LINE_HEIGHT_EM = 1.25f

        /** Clearance around the text, in ems. */
        const val PADDING_EM = 0.4f
    }
}

/** Reusable buffers for projecting the scene without allocating. */
class SceneScratch(
    capacity: Int,
) {
    val xs = DoubleArray(capacity)
    val ys = DoubleArray(capacity)
    val zs = DoubleArray(capacity)
    val point = FloatArray(2)

    fun put(
        index: Int,
        x: Double,
        y: Double,
        z: Double,
    ): Int {
        xs[index] = x
        ys[index] = y
        zs[index] = z
        return index + 1
    }
}

/** Vertices are packed as x, y, z triples. */
private const val STRIDE = 3
private const val Y = 1
private const val Z = 2
private const val MIN_POLYGON_VERTICES = 3

/** A crown lobe's outline: enough sides that a near tree's lobe stays round (under 1.5 px off at 150 px). */
private const val LOBE_SEGMENTS = 24
private val LOBE_COS = FloatArray(LOBE_SEGMENTS) { cos(2 * PI * it / LOBE_SEGMENTS).toFloat() }
private val LOBE_SIN = FloatArray(LOBE_SEGMENTS) { sin(2 * PI * it / LOBE_SEGMENTS).toFloat() }

/** A label is drawn while a metre spans at least this fraction of the canvas height (out to ~200 yd). */
private const val MIN_LABEL_PIXELS_PER_METER_FRACTION = 0.0045f

// Packed `0xAARRGGBB` colours (plan F8a2p's marker fade).
private const val ALPHA_SHIFT = 24
private const val RGB_MASK = 0x00FFFFFF
private const val HALF_STEP = 0.5f
