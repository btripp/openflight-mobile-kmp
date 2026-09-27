// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Plan F8a2a: where a shape's haze is measured from (a point on the ground, in scene metres) and
 * how strongly it hazes: 1 for grass and trees, less for the yardage targets so they stay readable.
 */
class HazeAnchor(
    val x: Double,
    val z: Double,
    val strength: Float = 1f,
) {
    /** The shape's [base] colour for a camera at ([cameraX], [cameraZ]), packed `0xAARRGGBB`. */
    fun argb(
        base: RangeColor,
        haze: RangeHaze,
        cameraX: Double,
        cameraZ: Double,
    ): Int = haze.argb(base, hypot(x - cameraX, z - cameraZ), strength)
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

    /** Re-colours it for a camera at ([cameraX], [cameraZ]); nothing without a haze anchor. Allocation-free. */
    fun tint(
        style: RangeHaze,
        cameraX: Double,
        cameraZ: Double,
    ) {
        haze?.let { argb = it.argb(color, style, cameraX, cameraZ) }
    }

    val vertexCount: Int get() = vertices.size / STRIDE

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        path.rewind()
        visible = false
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
            if (index == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
        }
        path.close()
        return true
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

    /** Plan F8a2a: re-colours the trunk and crowns for a camera at ([cameraX], [cameraZ]). */
    fun tint(
        haze: RangeHaze,
        cameraX: Double,
        cameraZ: Double,
    ) {
        trunk.tint(haze, cameraX, cameraZ)
        for (index in crowns.indices) {
            val crown = crowns[index]
            crown.argb = anchor.argb(crown.color, haze, cameraX, cameraZ)
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

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
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
