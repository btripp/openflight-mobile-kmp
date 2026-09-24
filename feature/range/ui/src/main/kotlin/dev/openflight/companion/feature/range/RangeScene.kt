// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A convex, flat shape of the scene in world (scene) space, with the one [Path] it is drawn with.
 * [project] rewrites that path for the current camera, clipping at the near plane
 * (Sutherland–Hodgman against one plane) so parts behind the camera don't wrap around. It
 * allocates nothing: the clip buffers are the scene's shared scratch arrays.
 */
internal class WorldPolygon(
    /** x, y, z triples. */
    val vertices: DoubleArray,
    val color: Color,
) {
    val path = Path()

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

/** A sphere drawn as a circle of its projected radius (a tree crown). */
internal class WorldSphere(
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val color: Color,
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

/** A tree: a trunk and two crowns, drawn together in back-to-front order. */
internal class WorldTree(
    val trunk: WorldPolygon,
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

/** A yardage label anchored above a marker; [scale] is the pixels one metre spans there. */
internal class WorldLabel(
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
}

/** Reusable buffers for projecting the scene without allocating. */
internal class SceneScratch(
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
 * under the follow camera (plan R7a), once per canvas size under the fixed one. Colours and
 * dimensions are the reference's (RangeSceneController.swift
 * `addGround`/`addTeeBox`/`addTargets`/`addTrees`).
 */
internal class RangeScene(
    description: RangeSceneDescription,
) {
    /** Ground-level shapes in drawing order (back to front along the range). */
    val polygons: List<WorldPolygon>
    val labels: List<WorldLabel>
    val trees: List<WorldTree>

    /** [trees] indices, far to near for the current camera, so nearer crowns overlap farther ones. */
    val treeOrder: IntArray

    val scratch = SceneScratch(capacity = DISC_SEGMENTS + 2)

    var horizonY = 0f
        private set

    init {
        val builder = Builder()
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
                            Trunk,
                        ),
                    crown =
                        WorldSphere(
                            tree.xMeters,
                            CROWN_Y * s,
                            z,
                            CROWN_RADIUS * s,
                            if (dark) CrownDark else CrownLight,
                        ),
                    crownTop =
                        WorldSphere(
                            tree.xMeters + CROWN_TOP_X * s,
                            CROWN_TOP_Y * s,
                            z + CROWN_TOP_Z * s,
                            CROWN_RADIUS * CROWN_TOP_SCALE * s,
                            if (dark) CrownLight else CrownDark,
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

    private class Builder {
        val polygons = mutableListOf<WorldPolygon>()
        val labels = mutableListOf<WorldLabel>()

        fun ground(description: RangeSceneDescription) {
            val depth = description.rangeDepthMeters
            // Ground box: 180 m wide, depth + 90 m long, centred at z = -(depth - 45) / 2.
            val groundCenter = -(depth - GROUND_BACK_MARGIN_METERS) / 2
            val groundHalfLength = (depth + GROUND_EXTRA_LENGTH_METERS) / 2
            polygons +=
                WorldPolygon(rectangle(0.0, groundCenter, GROUND_WIDTH_METERS / 2, groundHalfLength, 0.0), Ground)
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
                    Fairway,
                )
            for (index in 0 until STRIPE_COUNT) {
                val center = -(index * STRIPE_SPACING_METERS + STRIPE_OFFSET_METERS)
                polygons +=
                    WorldPolygon(
                        rectangle(0.0, center, description.fairwayWidthMeters / 2, STRIPE_LENGTH_METERS / 2, 0.0),
                        Stripe,
                    )
            }
        }

        /** The target line down the middle of the fairway, out to the last marker. */
        fun targetLine(description: RangeSceneDescription) {
            val end = (description.markers.maxOfOrNull { it.yards } ?: 0) * RangeSceneDescription.YARDS_TO_METERS
            polygons += WorldPolygon(rectangle(0.0, -end / 2, TARGET_LINE_HALF_WIDTH_METERS, end / 2, 0.0), TargetLine)
        }

        fun targets(description: RangeSceneDescription) {
            description.markers.forEachIndexed { index, marker ->
                val center = description.markerScenePositions[index]
                polygons += disc(center, marker.radiusMeters, 0.0, MarkerOuter)
                polygons +=
                    disc(
                        center,
                        marker.radiusMeters * MARKER_INNER_FRACTION,
                        0.0,
                        if (index % 2 == 0) MarkerRed else MarkerYellow,
                    )
                labels += WorldLabel(marker.yards.toString(), center.x, MARKER_LABEL_HEIGHT_METERS, center.z)
            }
        }

        fun teeBox() {
            polygons += WorldPolygon(rectangle(0.0, TEE_CENTER_Z, TEE_HALF_WIDTH, TEE_HALF_LENGTH, TEE_HEIGHT), Tee)
            for (x in listOf(-TEE_MARKER_X, TEE_MARKER_X)) {
                polygons += disc(Vec3(x, 0.0, 0.0), TEE_MARKER_RADIUS, TEE_HEIGHT + TEE_MARKER_LIFT_METERS, Color.White)
            }
        }
    }

    companion object {
        /** The landing marker's two discs (RangeSceneController.swift `buildLandingMarker`), built once per flight. */
        fun landingMarker(landing: Vec3): List<WorldPolygon> =
            listOf(
                disc(landing, LANDING_OUTER_RADIUS_METERS, LANDING_OUTER_HEIGHT_METERS, LandingOuter),
                disc(landing, LANDING_INNER_RADIUS_METERS, LANDING_INNER_HEIGHT_METERS, LandingInner),
            )

        private const val LANDING_OUTER_RADIUS_METERS = 2.2
        private const val LANDING_INNER_RADIUS_METERS = 1.35
        private const val LANDING_OUTER_HEIGHT_METERS = 0.065
        private const val LANDING_INNER_HEIGHT_METERS = 0.09
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
private fun disc(
    center: Vec3,
    radius: Double,
    y: Double,
    color: Color,
): WorldPolygon =
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

internal val Sky = Color(red = 0.34f, green = 0.63f, blue = 0.88f)
internal val SkyHorizon = Color(red = 0.68f, green = 0.84f, blue = 0.95f)
internal val Ground = Color(red = 0.08f, green = 0.30f, blue = 0.14f)
private val Fairway = Color(red = 0.20f, green = 0.52f, blue = 0.22f)
private val Stripe = Color(red = 0.25f, green = 0.59f, blue = 0.27f)
private val TargetLine = Color.White.copy(alpha = 0.35f)
private val Tee = Color(red = 0.16f, green = 0.47f, blue = 0.20f)
private val MarkerOuter = Color.White.copy(alpha = 0.88f)
private val MarkerRed = Color(red = 0.90f, green = 0.18f, blue = 0.15f)
private val MarkerYellow = Color(red = 0.96f, green = 0.72f, blue = 0.08f)
private val Trunk = Color(red = 0.29f, green = 0.16f, blue = 0.08f)
private val CrownDark = Color(red = 0.05f, green = 0.26f, blue = 0.10f)
private val CrownLight = Color(red = 0.07f, green = 0.34f, blue = 0.13f)
private val LandingOuter = Color.White.copy(alpha = 0.85f)
private val LandingInner = Color(red = 1f, green = 0.72f, blue = 0.06f, alpha = 0.95f)
