// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A convex, flat shape of the scene in world (scene) space, with the one [path] it is drawn with.
 * [project] rewrites that path for the current camera, clipping at the near plane
 * (Sutherland–Hodgman against one plane, at twice [RangeProjection.NEAR_PLANE_METERS]) so parts
 * behind the camera don't wrap around. It allocates nothing: the clip buffers are the scene's
 * shared scratch arrays. The platform fills [path] with [color] while [visible].
 */
class WorldPolygon<P : PathSink>(
    /** x, y, z triples. */
    val vertices: DoubleArray,
    val color: RangeColor,
    val path: P,
) {
    /** False when nothing of it is in front of the camera; [path] is empty then. */
    var visible = false
        private set

    val vertexCount: Int get() = vertices.size / STRIDE

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        path.rewind()
        val near = RangeProjection.NEAR_PLANE_METERS * 2
        val n = vertexCount
        var clipped = 0
        for (index in 0 until n) {
            val next = (index + 1) % n
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
        visible = clipped >= MIN_POLYGON_VERTICES
        if (!visible) return
        val point = scratch.point
        for (index in 0 until clipped) {
            projection.projectInto(scratch.xs[index], scratch.ys[index], scratch.zs[index], point, 0)
            if (index == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
        }
        path.close()
    }
}

/** A sphere drawn as a circle of its projected radius (a tree crown): a flat disc, no shading. */
class WorldSphere(
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val color: RangeColor,
) {
    var screenX = 0f
        private set
    var screenY = 0f
        private set
    var screenRadius = 0f
        private set
    var visible = false
        private set

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        val depth = projection.depth(x, y, z)
        visible = depth > RangeProjection.NEAR_PLANE_METERS + radius
        if (!visible) return
        projection.projectInto(x, y, z, scratch.point, 0)
        screenX = scratch.point[0]
        screenY = scratch.point[1]
        screenRadius = projection.pixels(radius, depth)
    }
}

/** A tree: a trunk and two crowns, drawn together (trunk, [crown], [crownTop]) in back-to-front order. */
class WorldTree<P : PathSink>(
    val trunk: WorldPolygon<P>,
    val crown: WorldSphere,
    val crownTop: WorldSphere,
    private val x: Double,
    private val z: Double,
) {
    var depth = 0.0
        private set

    fun project(
        projection: RangeProjection,
        scratch: SceneScratch,
    ) {
        depth = projection.depth(x, 0.0, z)
        trunk.project(projection, scratch)
        crown.project(projection, scratch)
        crownTop.project(projection, scratch)
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

/**
 * The range scene (ground, fairway stripes, target line, yardage targets, tee box, trees) in world
 * space, built once, and re-projected through [project] whenever the camera moves: every frame
 * under the follow camera (plan R7a), once per canvas size under the fixed one. Dimensions are the
 * reference's (RangeSceneController.swift `addGround`/`addTeeBox`/`addTargets`/`addTrees`); colours
 * come from [style].
 *
 * Shared by both renderers (plan F8c1). [newPath] makes each shape's platform [PathSink], once, at
 * construction (and in [landingMarker], once per flight). Draw order, back to front: the backdrop
 * ([backdropHorizon]), [polygons], then the trees in [treeOrder] (trunk, crown, crown top), then
 * the visible [labels].
 */
class RangeScene<P : PathSink>(
    description: RangeSceneDescription,
    val style: RangeVisualStyle,
    private val newPath: () -> P,
) {
    /** Ground-level shapes in drawing order (back to front along the range). */
    val polygons: List<WorldPolygon<P>>
    val labels: List<WorldLabel>
    val trees: List<WorldTree<P>>

    /** [trees] indices, far to near for the current camera, so nearer crowns overlap farther ones. */
    val treeOrder: IntArray

    val scratch = SceneScratch(capacity = DISC_SEGMENTS + 2)

    /** The horizon's canvas y at the last [project]; may lie off the canvas (±infinity looking straight down or up). */
    var horizonY = 0f
        private set

    init {
        val builder = Builder(style, newPath)
        builder.ground(description)
        builder.targetLine(description)
        builder.targets(description)
        builder.teeBox()
        polygons = builder.polygons
        labels = builder.labels
        trees =
            description.trees.sortedByDescending { it.downrangeMeters }.mapIndexed { index, tree ->
                val z = -tree.downrangeMeters
                val s = tree.scale
                val dark = index % 2 == 0
                WorldTree(
                    trunk =
                        WorldPolygon(
                            verticalQuad(tree.xMeters, z, TRUNK_HALF_WIDTH * s, 0.0, TRUNK_HEIGHT * s),
                            style.trunk,
                            newPath(),
                        ),
                    crown =
                        WorldSphere(
                            tree.xMeters,
                            CROWN_Y * s,
                            z,
                            CROWN_RADIUS * s,
                            if (dark) style.crownDark else style.crownLight,
                        ),
                    crownTop =
                        WorldSphere(
                            tree.xMeters + CROWN_TOP_X * s,
                            CROWN_TOP_Y * s,
                            z + CROWN_TOP_Z * s,
                            CROWN_RADIUS * CROWN_TOP_SCALE * s,
                            if (dark) style.crownLight else style.crownDark,
                        ),
                    x = tree.xMeters,
                    z = z,
                )
            }
        treeOrder = IntArray(trees.size) { it }
    }

    /** Re-projects everything for [projection]'s current camera. Allocation-free. */
    fun project(projection: RangeProjection) {
        for (index in polygons.indices) polygons[index].project(projection, scratch)
        for (index in labels.indices) labels[index].project(projection, scratch)
        for (index in trees.indices) trees[index].project(projection, scratch)
        sortTreesFarToNear()
        horizonY = projection.horizonY()
    }

    /**
     * The sky / distant-ground split on a [height]-pixel canvas: [horizonY] clamped to the canvas.
     * The sky gradient ([RangeVisualStyle.skyTop] → [RangeVisualStyle.skyHorizon]) spans 0 to it,
     * and [RangeVisualStyle.ground] fills it to [height] (the ground plane is finite).
     */
    fun backdropHorizon(height: Float): Float = horizonY.coerceIn(0f, height)

    /** The landing marker's two discs (RangeSceneController.swift `buildLandingMarker`), built once per flight. */
    fun landingMarker(landing: Vec3): List<WorldPolygon<P>> =
        listOf(
            disc(landing, LANDING_OUTER_RADIUS_METERS, LANDING_OUTER_HEIGHT_METERS, style.landingOuter, newPath()),
            disc(landing, LANDING_INNER_RADIUS_METERS, LANDING_INNER_HEIGHT_METERS, style.landingInner, newPath()),
        )

    /** Insertion sort (the order barely changes between frames, and it doesn't allocate). */
    private fun sortTreesFarToNear() {
        for (i in 1 until treeOrder.size) {
            val current = treeOrder[i]
            val depth = trees[current].depth
            var j = i - 1
            while (j >= 0 && trees[treeOrder[j]].depth < depth) {
                treeOrder[j + 1] = treeOrder[j]
                j--
            }
            treeOrder[j + 1] = current
        }
    }

    private class Builder<P : PathSink>(
        val style: RangeVisualStyle,
        val newPath: () -> P,
    ) {
        val polygons = mutableListOf<WorldPolygon<P>>()
        val labels = mutableListOf<WorldLabel>()

        fun ground(description: RangeSceneDescription) {
            val depth = description.rangeDepthMeters
            // Ground box: 180 m wide, depth + 90 m long, centred at z = -(depth - 45) / 2.
            val groundCenter = -(depth - GROUND_BACK_MARGIN_METERS) / 2
            val groundHalfLength = (depth + GROUND_EXTRA_LENGTH_METERS) / 2
            polygons +=
                WorldPolygon(
                    rectangle(0.0, groundCenter, GROUND_WIDTH_METERS / 2, groundHalfLength, 0.0),
                    style.ground,
                    newPath(),
                )
            // Fairway: fairway width x depth, centred at z = -depth / 2 + 8.
            polygons +=
                WorldPolygon(
                    rectangle(
                        0.0,
                        -depth / 2 + FAIRWAY_OFFSET_METERS,
                        description.fairwayWidthMeters / 2,
                        depth / 2,
                        0.0,
                    ),
                    style.fairway,
                    newPath(),
                )
            for (index in 0 until STRIPE_COUNT) {
                val center = -(index * STRIPE_SPACING_METERS + STRIPE_OFFSET_METERS)
                polygons +=
                    WorldPolygon(
                        rectangle(0.0, center, description.fairwayWidthMeters / 2, STRIPE_LENGTH_METERS / 2, 0.0),
                        style.stripe,
                        newPath(),
                    )
            }
        }

        /** The target line down the middle of the fairway, out to the last marker. */
        fun targetLine(description: RangeSceneDescription) {
            val end = (description.markers.maxOfOrNull { it.yards } ?: 0) * RangeSceneDescription.YARDS_TO_METERS
            polygons +=
                WorldPolygon(
                    rectangle(0.0, -end / 2, TARGET_LINE_HALF_WIDTH_METERS, end / 2, 0.0),
                    style.targetLine,
                    newPath(),
                )
        }

        fun targets(description: RangeSceneDescription) {
            description.markers.forEachIndexed { index, marker ->
                val center = description.markerScenePositions[index]
                polygons += disc(center, marker.radiusMeters, 0.0, style.markerOuter, newPath())
                polygons +=
                    disc(
                        center,
                        marker.radiusMeters * MARKER_INNER_FRACTION,
                        0.0,
                        if (index % 2 == 0) style.markerRed else style.markerYellow,
                        newPath(),
                    )
                labels += WorldLabel(marker.yards.toString(), center.x, MARKER_LABEL_HEIGHT_METERS, center.z)
            }
        }

        fun teeBox() {
            polygons +=
                WorldPolygon(
                    rectangle(0.0, TEE_CENTER_Z, TEE_HALF_WIDTH, TEE_HALF_LENGTH, TEE_HEIGHT),
                    style.tee,
                    newPath(),
                )
            for (x in listOf(-TEE_MARKER_X, TEE_MARKER_X)) {
                polygons +=
                    disc(
                        Vec3(x, 0.0, 0.0),
                        TEE_MARKER_RADIUS,
                        TEE_HEIGHT + TEE_MARKER_LIFT_METERS,
                        style.teeMarker,
                        newPath(),
                    )
            }
        }
    }

    private companion object {
        const val LANDING_OUTER_RADIUS_METERS = 2.2
        const val LANDING_INNER_RADIUS_METERS = 1.35
        const val LANDING_OUTER_HEIGHT_METERS = 0.065
        const val LANDING_INNER_HEIGHT_METERS = 0.09
    }
}

/** A ground-plane rectangle's vertices (x ± [halfWidth], z ± [halfLength], height [y]). */
private fun rectangle(
    centerX: Double,
    centerZ: Double,
    halfWidth: Double,
    halfLength: Double,
    y: Double,
): DoubleArray =
    doubleArrayOf(
        centerX - halfWidth,
        y,
        centerZ + halfLength,
        centerX + halfWidth,
        y,
        centerZ + halfLength,
        centerX + halfWidth,
        y,
        centerZ - halfLength,
        centerX - halfWidth,
        y,
        centerZ - halfLength,
    )

/** A flat disc on the ground (a flattened sphere in the reference). */
private fun <P : PathSink> disc(
    center: Vec3,
    radius: Double,
    y: Double,
    color: RangeColor,
    path: P,
): WorldPolygon<P> =
    WorldPolygon(
        DoubleArray(DISC_SEGMENTS * STRIDE) { component ->
            val angle = 2 * PI * (component / STRIDE) / DISC_SEGMENTS
            when (component % STRIDE) {
                X -> center.x + radius * cos(angle)
                Y -> y
                else -> center.z + radius * sin(angle)
            }
        },
        color,
        path,
    )

/** A rectangle standing on the ground across the range (a tree trunk), as vertices. */
private fun verticalQuad(
    x: Double,
    z: Double,
    halfWidth: Double,
    bottom: Double,
    top: Double,
): DoubleArray =
    doubleArrayOf(x - halfWidth, bottom, z, x + halfWidth, bottom, z, x + halfWidth, top, z, x - halfWidth, top, z)

/** Vertices are packed as x, y, z triples. */
private const val STRIDE = 3
private const val X = 0
private const val Y = 1
private const val Z = 2
private const val DISC_SEGMENTS = 36
private const val MIN_POLYGON_VERTICES = 3
private const val TEE_MARKER_LIFT_METERS = 0.01
private const val GROUND_WIDTH_METERS = 180.0
private const val GROUND_BACK_MARGIN_METERS = 45.0
private const val GROUND_EXTRA_LENGTH_METERS = 90.0
private const val FAIRWAY_OFFSET_METERS = 8.0
private const val STRIPE_COUNT = 11
private const val STRIPE_SPACING_METERS = 36.0
private const val STRIPE_OFFSET_METERS = 12.0
private const val STRIPE_LENGTH_METERS = 18.0
private const val TARGET_LINE_HALF_WIDTH_METERS = 0.12
private const val MARKER_INNER_FRACTION = 0.48
private const val MARKER_LABEL_HEIGHT_METERS = 2.5

/** A label is drawn while a metre spans at least this fraction of the canvas height (out to ~200 yd). */
private const val MIN_LABEL_PIXELS_PER_METER_FRACTION = 0.0045f
private const val TEE_CENTER_Z = 2.5
private const val TEE_HALF_WIDTH = 7.5
private const val TEE_HALF_LENGTH = 5.5
private const val TEE_HEIGHT = 0.16
private const val TEE_MARKER_X = 4.0
private const val TEE_MARKER_RADIUS = 0.33
private const val TRUNK_HALF_WIDTH = 0.45
private const val TRUNK_HEIGHT = 4.8
private const val CROWN_Y = 6.2
private const val CROWN_RADIUS = 2.8
private const val CROWN_TOP_SCALE = 0.68
private const val CROWN_TOP_X = 0.7
private const val CROWN_TOP_Y = 8.2
private const val CROWN_TOP_Z = -0.3
