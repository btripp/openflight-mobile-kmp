// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.RangeTreeDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The range scene (ground, fairway stripes, grass mottling, target line, yardage targets, tee box,
 * trees and their shadows, and the [sky]'s sun and far ridges) in world space, built once, and
 * re-projected through [project] whenever the camera moves: every frame under the follow camera
 * (plan R7a), once per canvas size under the fixed one. Dimensions are the reference's
 * (RangeSceneController.swift `addGround`/`addTeeBox`/`addTargets`/`addTrees`); colours come from
 * [style].
 *
 * Plan F8a2a, the atmosphere pass: the ground and fairway are split into depth bands, and each
 * band, stripe, target and tree is re-coloured with every re-projection, hazed by its distance
 * from the camera ([RangeVisualStyle.haze], [WorldPolygon.argb]); frames that don't move the
 * camera change no colour. The rough runs out to the horizon, grass mottling and trees' contact
 * shadows are one multi-ring polygon each, and every tree is a trunk and three lobed crown tones
 * whose shape, size and shade vary by a seed.
 *
 * Shared by both renderers (plan F8c1). [newPath] makes each shape's platform [PathSink], once, at
 * construction (and in [landingMarker], once per flight). Draw order, back to front: the backdrop
 * (the sky gradient to [backdropHorizon], the [sky], the distant ground band below it),
 * [polygons], then the trees in [treeOrder] (trunk, then each crown tone), then the visible
 * [labels].
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

    /** The sun and the far ridges (plan F8a2a). */
    val sky: RangeSky<P> = RangeSky(style, newPath)

    /** [trees] indices, far to near for the current camera, so nearer crowns overlap farther ones. */
    val treeOrder: IntArray

    val scratch = SceneScratch(capacity = DISC_SEGMENTS + 2)

    /** The horizon's canvas y at the last [project]; may lie off the canvas (±infinity looking straight down or up). */
    var horizonY = 0f
        private set

    init {
        val builder = Builder(style, newPath)
        builder.ground(description)
        builder.mottling()
        builder.treeShadows(description.trees)
        builder.targetLine(description)
        builder.targets(description)
        builder.teeBox()
        polygons = builder.polygons
        labels = builder.labels
        trees =
            description.trees
                .sortedByDescending { it.downrangeMeters }
                .mapIndexed { index, tree -> builder.tree(tree, index) }
        treeOrder = IntArray(trees.size) { it }
    }

    /** Re-projects everything for [projection]'s current camera. Allocation-free. */
    fun project(projection: RangeProjection) {
        for (index in polygons.indices) polygons[index].project(projection, scratch)
        for (index in labels.indices) labels[index].project(projection, scratch)
        for (index in trees.indices) trees[index].project(projection, scratch)
        tint(projection.cameraX, projection.cameraZ)
        sortTreesFarToNear()
        horizonY = projection.horizonY()
        sky.project(projection, horizonY)
    }

    /**
     * The sky / distant-ground split on a [height]-pixel canvas: [horizonY] clamped to the canvas.
     * The sky gradient ([RangeVisualStyle.sky]) spans 0 to it, and [RangeVisualStyle.distantGround]
     * fills it to [height] (past the ground plane's far end).
     */
    fun backdropHorizon(height: Float): Float = horizonY.coerceIn(0f, height)

    /** The landing marker's two discs (RangeSceneController.swift `buildLandingMarker`), built once per flight. */
    fun landingMarker(landing: Vec3): List<WorldPolygon<P>> =
        listOf(
            disc(landing, LANDING_OUTER_RADIUS_METERS, LANDING_OUTER_HEIGHT_METERS, style.landingOuter, newPath()),
            disc(landing, LANDING_INNER_RADIUS_METERS, LANDING_INNER_HEIGHT_METERS, style.landingInner, newPath()),
        )

    /** Plan F8a2a: every hazed shape's colour for a camera at ([cameraX], [cameraZ]). */
    private fun tint(
        cameraX: Double,
        cameraZ: Double,
    ) {
        for (index in polygons.indices) polygons[index].tint(style.haze, cameraX, cameraZ)
        for (index in trees.indices) trees[index].tint(style.haze, cameraX, cameraZ)
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

    private class Builder<P : PathSink>(
        val style: RangeVisualStyle,
        val newPath: () -> P,
    ) {
        val polygons = mutableListOf<WorldPolygon<P>>()
        val labels = mutableListOf<WorldLabel>()

        /** The sun's horizontal direction: highlights face it, shadows fall away from it. */
        private val sunX = sin(style.sun.azimuthDegrees * PI / DEGREES_PER_HALF_TURN)
        private val sunZ = -cos(style.sun.azimuthDegrees * PI / DEGREES_PER_HALF_TURN)

        /** Plan F8a2a: the rough and the fairway in hazed depth bands, then the mowing stripes. */
        fun ground(description: RangeSceneDescription) {
            for (band in 0 until GROUND_BAND_EDGES.size - 1) {
                val near = GROUND_BAND_EDGES[band]
                val far = GROUND_BAND_EDGES[band + 1]
                val halfWidth = if (band == 0) NEAR_GROUND_HALF_WIDTH_METERS else FAR_GROUND_HALF_WIDTH_METERS
                polygons += band(near, far, halfWidth, style.ground)
            }
            val fairwayEnd = description.rangeDepthMeters - FAIRWAY_OFFSET_METERS
            val halfWidth = description.fairwayWidthMeters / 2
            for (band in 0 until FAIRWAY_BAND_EDGES.size) {
                val near = if (band == 0) -FAIRWAY_OFFSET_METERS else FAIRWAY_BAND_EDGES[band - 1]
                val far = if (band == FAIRWAY_BAND_EDGES.size - 1) fairwayEnd else FAIRWAY_BAND_EDGES[band]
                polygons += band(near, far, halfWidth, style.fairway)
            }
            for (index in 0 until STRIPE_COUNT) {
                val distance = index * STRIPE_SPACING_METERS + STRIPE_OFFSET_METERS
                polygons +=
                    WorldPolygon(
                        rectangle(0.0, -distance, halfWidth, STRIPE_LENGTH_METERS / 2, 0.0),
                        style.stripe,
                        newPath(),
                        haze = HazeAnchor(0.0, -distance),
                    )
            }
        }

        /** A ground rectangle across the range from [near] to [far] metres downrange, hazed from its middle. */
        private fun band(
            near: Double,
            far: Double,
            halfWidth: Double,
            color: RangeColor,
        ): WorldPolygon<P> =
            WorldPolygon(
                rectangle(0.0, -(near + far) / 2, halfWidth, (far - near) / 2, 0.0),
                color,
                newPath(),
                haze = HazeAnchor(0.0, -(near + far) / 2),
            )

        /**
         * Plan F8a2a: low-alpha light and dark patches in the grass, each set one polygon of many
         * irregular rings (one fill each), scattered by a fixed seed.
         */
        fun mottling() {
            polygons += patches(MOTTLE_LIGHT_SEED, style.mottleLight)
            polygons += patches(MOTTLE_DARK_SEED, style.mottleDark)
        }

        private fun patches(
            seed: Int,
            color: RangeColor,
        ): WorldPolygon<P> {
            val vertices = DoubleArray(MOTTLE_PATCHES * MOTTLE_SIDES * STRIDE)
            var at = 0
            for (patch in 0 until MOTTLE_PATCHES) {
                val x = -MOTTLE_HALF_SPREAD_METERS + unitHash(seed, patch * HASH_SLOTS) * 2 * MOTTLE_HALF_SPREAD_METERS
                val distance =
                    MOTTLE_NEAR_METERS +
                        unitHash(seed, patch * HASH_SLOTS + SLOT_DISTANCE) * (MOTTLE_FAR_METERS - MOTTLE_NEAR_METERS)
                val radiusX =
                    MOTTLE_MIN_RADIUS_METERS +
                        unitHash(seed, patch * HASH_SLOTS + SLOT_RADIUS) * MOTTLE_RADIUS_SPREAD_METERS
                val radiusZ =
                    radiusX *
                        (MOTTLE_MIN_ASPECT + unitHash(seed, patch * HASH_SLOTS + SLOT_ASPECT) * MOTTLE_ASPECT_SPREAD)
                for (side in 0 until MOTTLE_SIDES) {
                    val angle = 2 * PI * side / MOTTLE_SIDES
                    val wobble = MOTTLE_MIN_WOBBLE + unitHash(seed + side + 1, patch) * MOTTLE_WOBBLE_SPREAD
                    vertices[at++] = x + radiusX * wobble * cos(angle)
                    vertices[at++] = MOTTLE_LIFT_METERS
                    vertices[at++] = -distance + radiusZ * wobble * sin(angle)
                }
            }
            return WorldPolygon(vertices, color, newPath(), IntArray(MOTTLE_PATCHES) { MOTTLE_SIDES })
        }

        /** Plan F8a2a: every tree's soft contact shadow, cast away from the sun, as one polygon. */
        fun treeShadows(trees: List<RangeTreeDescription>) {
            if (trees.isEmpty()) return
            val vertices = DoubleArray(trees.size * SHADOW_SIDES * STRIDE)
            var at = 0
            for (tree in trees) {
                val s = tree.scale
                val centerX = tree.xMeters - sunX * SHADOW_OFFSET_METERS * s
                val centerZ = -tree.downrangeMeters - sunZ * SHADOW_OFFSET_METERS * s
                for (side in 0 until SHADOW_SIDES) {
                    val angle = 2 * PI * side / SHADOW_SIDES
                    vertices[at++] = centerX + SHADOW_RADIUS_X_METERS * s * cos(angle)
                    vertices[at++] = SHADOW_LIFT_METERS
                    vertices[at++] = centerZ + SHADOW_RADIUS_Z_METERS * s * sin(angle)
                }
            }
            polygons += WorldPolygon(vertices, style.treeShadow, newPath(), IntArray(trees.size) { SHADOW_SIDES })
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

        /** The yardage targets, half-hazed so the far ones recede but stay readable. */
        fun targets(description: RangeSceneDescription) {
            description.markers.forEachIndexed { index, marker ->
                val center = description.markerScenePositions[index]
                val anchor = HazeAnchor(center.x, center.z, MARKER_HAZE_STRENGTH)
                polygons += disc(center, marker.radiusMeters, 0.0, style.markerOuter, newPath(), anchor)
                polygons +=
                    disc(
                        center,
                        marker.radiusMeters * MARKER_INNER_FRACTION,
                        0.0,
                        if (index % 2 == 0) style.markerRed else style.markerYellow,
                        newPath(),
                        anchor,
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

        /**
         * Plan F8a2a: a trunk and three crown tones (a shaded underside, the body, a sunlit top
         * facing the sun), each a few lobes whose placement, size and count vary with the tree's
         * [index]; the tree's colours vary in brightness by the same seed (and are hazed per pose).
         */
        fun tree(
            tree: RangeTreeDescription,
            index: Int,
        ): WorldTree<P> {
            val x = tree.xMeters
            val z = -tree.downrangeMeters
            val s = tree.scale
            val brightness = (TREE_MIN_BRIGHTNESS + unitHash(TREE_SEED, index) * TREE_BRIGHTNESS_SPREAD).toFloat()
            val tall = TREE_MIN_HEIGHT + unitHash(TREE_SEED + 1, index) * TREE_HEIGHT_SPREAD

            fun tone(color: RangeColor) = color.scaled(brightness)
            val sunSide = if (sunX < 0) -1.0 else 1.0

            fun lobes(
                template: DoubleArray,
                salt: Int,
                keep: Int,
            ): DoubleArray {
                val count = template.size / WorldCrown.LOBE_STRIDE
                // The first lobe always stays; the rest drop out by the seed, but no fewer than `keep`.
                var kept = 0
                val out = DoubleArray(template.size)
                for (lobe in 0 until count) {
                    val h = unitHash(TREE_SEED + salt, index * HASH_SLOTS + lobe)
                    if (lobe >= keep && h < LOBE_DROP_CHANCE) continue
                    val base = lobe * WorldCrown.LOBE_STRIDE
                    val jitterX =
                        (unitHash(TREE_SEED + salt + 1, index * HASH_SLOTS + lobe) - HALF) * LOBE_JITTER_METERS
                    val jitterY = (h - HALF) * LOBE_JITTER_METERS * HALF
                    val sizeJitter =
                        LOBE_MIN_SIZE + unitHash(TREE_SEED + salt + 2, index * HASH_SLOTS + lobe) * LOBE_SIZE_SPREAD
                    val side = if (template[base] == SUN_SIDE) sunSide * LOBE_SUN_OFFSET_METERS else template[base]
                    out[kept * WorldCrown.LOBE_STRIDE] = x + (side + jitterX) * s
                    out[kept * WorldCrown.LOBE_STRIDE + LOBE_Y] = (template[base + LOBE_Y] * tall + jitterY) * s
                    out[kept * WorldCrown.LOBE_STRIDE + LOBE_Z] = z + template[base + LOBE_Z] * s
                    out[kept * WorldCrown.LOBE_STRIDE + LOBE_RADIUS] = template[base + LOBE_RADIUS] * sizeJitter * s
                    kept++
                }
                return out.copyOf(kept * WorldCrown.LOBE_STRIDE)
            }

            return WorldTree(
                trunk =
                    WorldPolygon(
                        verticalQuad(x, z, TRUNK_HALF_WIDTH * s, 0.0, TRUNK_HEIGHT * tall * s),
                        tone(style.trunk),
                        newPath(),
                        haze = HazeAnchor(x, z),
                    ),
                crowns =
                    listOf(
                        WorldCrown(lobes(DARK_LOBES, salt = 10, keep = 2), tone(style.crownDark), newPath()),
                        WorldCrown(lobes(MID_LOBES, salt = 20, keep = 2), tone(style.crownMid), newPath()),
                        WorldCrown(lobes(LIGHT_LOBES, salt = 30, keep = 1), tone(style.crownLight), newPath()),
                    ),
                x = x,
                z = z,
            )
        }
    }

    private companion object {
        const val LANDING_OUTER_RADIUS_METERS = 2.2
        const val LANDING_INNER_RADIUS_METERS = 1.35
        const val LANDING_OUTER_HEIGHT_METERS = 0.065
        const val LANDING_INNER_HEIGHT_METERS = 0.09
        const val DEGREES_PER_HALF_TURN = 180.0

        /**
         * Plan F8a2a: the rough's depth bands, in metres down the range from the tee. The first
         * runs behind the camera; the last reaches far enough that its far edge sits within a few
         * pixels of the horizon.
         */
        val GROUND_BAND_EDGES = doubleArrayOf(-150.0, 70.0, 150.0, 250.0, 380.0, 550.0, 800.0, 1200.0, 2000.0, 6000.0)
        const val NEAR_GROUND_HALF_WIDTH_METERS = 600.0
        const val FAR_GROUND_HALF_WIDTH_METERS = 3000.0

        /** The fairway's band edges, in metres downrange: every two mowing stripes. */
        val FAIRWAY_BAND_EDGES = doubleArrayOf(30.0, 102.0, 174.0, 246.0, 318.0, 382.0)

        const val MARKER_HAZE_STRENGTH = 0.5f
        const val TREE_SEED = 7_919
        const val TREE_MIN_BRIGHTNESS = 0.88
        const val TREE_BRIGHTNESS_SPREAD = 0.24
        const val TREE_MIN_HEIGHT = 0.9
        const val TREE_HEIGHT_SPREAD = 0.3
        const val LOBE_DROP_CHANCE = 0.4
        const val LOBE_JITTER_METERS = 0.8
        const val LOBE_MIN_SIZE = 0.85
        const val LOBE_SIZE_SPREAD = 0.3
        const val LOBE_SUN_OFFSET_METERS = 0.9
        const val HALF = 0.5
        const val HASH_SLOTS = 8

        // A mottle patch's hash slots, and a lobe's fields after its x.
        const val SLOT_DISTANCE = 1
        const val SLOT_RADIUS = 2
        const val SLOT_ASPECT = 3
        const val LOBE_Y = 1
        const val LOBE_Z = 2
        const val LOBE_RADIUS = 3

        /** A template lobe's x at this value sits on the sun's side of the tree. */
        const val SUN_SIDE = 99.0

        // Crown lobes at scale 1: x, y, z, radius (metres; x and z relative to the trunk).
        val DARK_LOBES = doubleArrayOf(-1.3, 5.3, 0.3, 2.2, 1.3, 5.1, 0.2, 2.1, 0.0, 4.9, 0.4, 1.9)
        val MID_LOBES = doubleArrayOf(0.0, 6.5, 0.0, 2.7, -1.0, 7.8, -0.2, 2.0, 1.1, 7.3, -0.1, 1.8)
        val LIGHT_LOBES = doubleArrayOf(SUN_SIDE, 8.3, -0.4, 1.35, 0.1, 9.2, -0.5, 0.95)

        const val MOTTLE_LIGHT_SEED = 101
        const val MOTTLE_DARK_SEED = 211
        const val MOTTLE_PATCHES = 8
        const val MOTTLE_SIDES = 7
        const val MOTTLE_HALF_SPREAD_METERS = 75.0
        const val MOTTLE_NEAR_METERS = 15.0
        const val MOTTLE_FAR_METERS = 250.0
        const val MOTTLE_MIN_RADIUS_METERS = 5.0
        const val MOTTLE_RADIUS_SPREAD_METERS = 9.0
        const val MOTTLE_MIN_ASPECT = 0.6
        const val MOTTLE_ASPECT_SPREAD = 0.5
        const val MOTTLE_MIN_WOBBLE = 0.8
        const val MOTTLE_WOBBLE_SPREAD = 0.3
        const val MOTTLE_LIFT_METERS = 0.005

        const val SHADOW_SIDES = 12
        const val SHADOW_OFFSET_METERS = 1.4
        const val SHADOW_RADIUS_X_METERS = 3.2
        const val SHADOW_RADIUS_Z_METERS = 2.2
        const val SHADOW_LIFT_METERS = 0.01
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
    haze: HazeAnchor? = null,
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
        haze = haze,
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
private const val DISC_SEGMENTS = 36
private const val TEE_MARKER_LIFT_METERS = 0.01
private const val FAIRWAY_OFFSET_METERS = 8.0
private const val STRIPE_COUNT = 11
private const val STRIPE_SPACING_METERS = 36.0
private const val STRIPE_OFFSET_METERS = 12.0
private const val STRIPE_LENGTH_METERS = 18.0
private const val TARGET_LINE_HALF_WIDTH_METERS = 0.12
private const val MARKER_INNER_FRACTION = 0.48
private const val MARKER_LABEL_HEIGHT_METERS = 2.5
private const val TEE_CENTER_Z = 2.5
private const val TEE_HALF_WIDTH = 7.5
private const val TEE_HALF_LENGTH = 5.5
private const val TEE_HEIGHT = 0.16
private const val TEE_MARKER_X = 4.0
private const val TEE_MARKER_RADIUS = 0.33
private const val TRUNK_HALF_WIDTH = 0.45
private const val TRUNK_HEIGHT = 4.8
