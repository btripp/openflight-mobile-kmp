// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A filled shape in the static scene. */
internal class ScenePolygon(
    val path: Path,
    val color: Color,
)

/** A yardage label anchored above a marker. */
internal class SceneLabel(
    val text: String,
    val anchor: Offset,
    val scale: Float,
)

/**
 * The range scene (ground, fairway stripes, tee box, yardage targets, trees) projected once per
 * canvas size, in back-to-front order. Colours and dimensions are the reference's
 * (RangeSceneController.swift `addGround`/`addTeeBox`/`addTargets`/`addTrees`).
 */
internal class RangeScene(
    val horizonY: Float,
    val polygons: List<ScenePolygon>,
    val labels: List<SceneLabel>,
) {
    companion object {
        fun build(
            projection: RangeProjection,
            description: RangeSceneDescription,
        ): RangeScene {
            val builder = Builder(projection)
            builder.ground(description)
            builder.targetLine(description)
            builder.targets(description)
            builder.teeBox()
            builder.trees(description)
            val horizon = projection.project(0.0, 0.0, -HORIZON_DISTANCE_METERS)
            return RangeScene(horizon.y, builder.polygons, builder.labels)
        }

        /** The landing marker's two discs (RangeSceneController.swift `buildLandingMarker`). */
        fun landingMarker(
            projection: RangeProjection,
            landing: Vec3,
        ): List<ScenePolygon> =
            listOfNotNull(
                projection.disc(landing, LANDING_OUTER_RADIUS_METERS, LANDING_OUTER_HEIGHT_METERS)?.let {
                    ScenePolygon(it, LandingOuter)
                },
                projection.disc(landing, LANDING_INNER_RADIUS_METERS, LANDING_INNER_HEIGHT_METERS)?.let {
                    ScenePolygon(it, LandingInner)
                },
            )

        private const val HORIZON_DISTANCE_METERS = 100_000.0
        private const val LANDING_OUTER_RADIUS_METERS = 2.2
        private const val LANDING_INNER_RADIUS_METERS = 1.35
        private const val LANDING_OUTER_HEIGHT_METERS = 0.065
        private const val LANDING_INNER_HEIGHT_METERS = 0.09
    }

    private class Builder(
        private val projection: RangeProjection,
    ) {
        val polygons = mutableListOf<ScenePolygon>()
        val labels = mutableListOf<SceneLabel>()

        fun add(
            path: Path?,
            color: Color,
        ) {
            if (path != null) polygons += ScenePolygon(path, color)
        }

        fun ground(description: RangeSceneDescription) {
            val depth = description.rangeDepthMeters
            // Ground box: 180 m wide, depth + 90 m long, centred at z = -(depth - 45) / 2.
            val groundCenter = -(depth - GROUND_BACK_MARGIN_METERS) / 2
            val groundHalfLength = (depth + GROUND_EXTRA_LENGTH_METERS) / 2
            add(
                projection.rectangle(0.0, groundCenter, GROUND_WIDTH_METERS / 2, groundHalfLength, 0.0),
                Ground,
            )
            // Fairway: fairway width x depth, centred at z = -depth / 2 + 8.
            add(
                projection.rectangle(
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
                add(
                    projection.rectangle(
                        0.0,
                        center,
                        description.fairwayWidthMeters / 2,
                        STRIPE_LENGTH_METERS / 2,
                        0.0,
                    ),
                    Stripe,
                )
            }
        }

        /** The target line down the middle of the fairway, out to the last marker. */
        fun targetLine(description: RangeSceneDescription) {
            val end = (description.markers.maxOfOrNull { it.yards } ?: 0) * YARDS_TO_METERS
            add(projection.rectangle(0.0, -end / 2, TARGET_LINE_HALF_WIDTH_METERS, end / 2, 0.0), TargetLine)
        }

        fun targets(description: RangeSceneDescription) {
            description.markers.forEachIndexed { index, marker ->
                val distance = marker.yards * YARDS_TO_METERS
                val x = if (index % 2 == 0) -MARKER_OFFSET_METERS else MARKER_OFFSET_METERS
                val center = Vec3(x, 0.0, -distance)
                add(projection.disc(center, marker.radiusMeters, 0.0), MarkerOuter)
                add(
                    projection.disc(center, marker.radiusMeters * MARKER_INNER_FRACTION, 0.0),
                    if (index % 2 == 0) MarkerRed else MarkerYellow,
                )
                val anchor = projection.project(x, MARKER_LABEL_HEIGHT_METERS, -distance)
                val depth = projection.depth(x, 0.0, -distance)
                val scale = if (depth > RangeProjection.NEAR_PLANE_METERS) projection.pixels(1.0, depth) else 0f
                // Far labels bunch up at the horizon, so only readable (near enough) ones are drawn.
                if (scale >= projection.height * MIN_LABEL_PIXELS_PER_METER_FRACTION) {
                    labels += SceneLabel(marker.yards.toString(), anchor.toOffset(), scale)
                }
            }
        }

        fun teeBox() {
            add(projection.rectangle(0.0, TEE_CENTER_Z, TEE_HALF_WIDTH, TEE_HALF_LENGTH, TEE_HEIGHT), Tee)
            for (x in listOf(-TEE_MARKER_X, TEE_MARKER_X)) {
                add(
                    projection.disc(Vec3(x, 0.0, 0.0), TEE_MARKER_RADIUS, TEE_HEIGHT + TEE_MARKER_LIFT_METERS),
                    Color.White,
                )
            }
        }

        /** Trees far to near, so nearer crowns overlap farther ones. */
        fun trees(description: RangeSceneDescription) {
            val sorted = description.trees.sortedByDescending { it.downrangeMeters }
            sorted.forEachIndexed { index, tree ->
                val z = -tree.downrangeMeters
                val s = tree.scale
                add(
                    projection.verticalQuad(tree.xMeters, z, TRUNK_HALF_WIDTH * s, 0.0, TRUNK_HEIGHT * s),
                    Trunk,
                )
                val crown = if (index % 2 == 0) CrownDark else CrownLight
                val crownTop = if (index % 2 == 0) CrownLight else CrownDark
                add(projection.sphere(Vec3(tree.xMeters, CROWN_Y * s, z), CROWN_RADIUS * s), crown)
                add(
                    projection.sphere(
                        Vec3(tree.xMeters + CROWN_TOP_X * s, CROWN_TOP_Y * s, z + CROWN_TOP_Z * s),
                        CROWN_RADIUS * CROWN_TOP_SCALE * s,
                    ),
                    crownTop,
                )
            }
        }
    }
}

/**
 * A ground-plane rectangle (x ± [halfWidth], z ± [halfLength], height [y]), clipped at the near
 * plane so the parts behind the camera don't wrap around.
 */
internal fun RangeProjection.rectangle(
    centerX: Double,
    centerZ: Double,
    halfWidth: Double,
    halfLength: Double,
    y: Double,
): Path? =
    polygon(
        listOf(
            Vec3(centerX - halfWidth, y, centerZ + halfLength),
            Vec3(centerX + halfWidth, y, centerZ + halfLength),
            Vec3(centerX + halfWidth, y, centerZ - halfLength),
            Vec3(centerX - halfWidth, y, centerZ - halfLength),
        ),
    )

/** A flat disc on the ground (a flattened sphere in the reference). */
internal fun RangeProjection.disc(
    center: Vec3,
    radius: Double,
    y: Double,
): Path? =
    polygon(
        List(DISC_SEGMENTS) { index ->
            val angle = 2 * PI * index / DISC_SEGMENTS
            Vec3(center.x + radius * cos(angle), y, center.z + radius * sin(angle))
        },
    )

/** A camera-facing rectangle standing on the ground (a tree trunk). */
internal fun RangeProjection.verticalQuad(
    x: Double,
    z: Double,
    halfWidth: Double,
    bottom: Double,
    top: Double,
): Path? =
    polygon(
        listOf(
            Vec3(x - halfWidth, bottom, z),
            Vec3(x + halfWidth, bottom, z),
            Vec3(x + halfWidth, top, z),
            Vec3(x - halfWidth, top, z),
        ),
    )

/** A sphere's silhouette: a circle of the projected radius. */
internal fun RangeProjection.sphere(
    center: Vec3,
    radius: Double,
): Path? {
    val depth = depth(center.x, center.y, center.z)
    if (depth <= RangeProjection.NEAR_PLANE_METERS + radius) return null
    val screen = project(center)
    val pixels = pixels(radius, depth)
    return Path().apply {
        addOval(Rect(center = screen.toOffset(), radius = pixels))
    }
}

/** Projects a convex polygon after clipping it at the near plane (Sutherland–Hodgman against one plane). */
internal fun RangeProjection.polygon(vertices: List<Vec3>): Path? {
    val near = RangeProjection.NEAR_PLANE_METERS * 2
    val clipped = ArrayList<Vec3>(vertices.size + 2)
    for (index in vertices.indices) {
        val current = vertices[index]
        val next = vertices[(index + 1) % vertices.size]
        val currentDepth = depth(current.x, current.y, current.z)
        val nextDepth = depth(next.x, next.y, next.z)
        if (currentDepth >= near) clipped += current
        if ((currentDepth >= near) != (nextDepth >= near)) {
            val t = (near - currentDepth) / (nextDepth - currentDepth)
            clipped += current + (next - current) * t
        }
    }
    if (clipped.size < MIN_POLYGON_VERTICES) return null
    return Path().apply {
        clipped.forEachIndexed { index, vertex ->
            val point = project(vertex)
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
}

internal const val YARDS_TO_METERS = 0.9144
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
private const val MARKER_OFFSET_METERS = 9.0
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
private val Ground = Color(red = 0.08f, green = 0.30f, blue = 0.14f)
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
