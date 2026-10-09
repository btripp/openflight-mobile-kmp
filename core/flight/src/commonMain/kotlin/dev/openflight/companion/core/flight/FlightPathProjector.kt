// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The shot detail's camera angles on a [ShotFlightPath]. */
enum class FlightView(
    val label: String,
) {
    /** From behind the golfer, like a TV tracer; the camera orbits with a drag. */
    BEHIND("Behind"),

    /** From overhead, tee at the bottom: the shape at a glance. */
    TOP("Top"),

    /** From the side, tee on the left: the height. */
    SIDE("Side"),
}

/** A point in a view's drawing space: pixels (or points) from the top-left corner, y down. */
data class ScreenPoint(
    val x: Float,
    val y: Float,
)

/** A distance marker on the target line, e.g. the 150 yd mark. */
data class FlightMarker(
    val position: ScreenPoint,
    val yards: Int,
)

/**
 * A [ShotFlightPath] laid out in one [FlightView], ready to stroke: Compose and SwiftUI only draw
 * these lines, so both platforms show the same picture.
 *
 * @property path tee to landing, one point per [ShotFlightPath.points] sample.
 * @property roll landing to rest; empty when the shot has no roll.
 * @property targetLine the target line from the tee to past the ball.
 * @property guides the ground's edges either side of the target line; from behind they can run
 *   past the box, so draw them clipped.
 */
data class ProjectedFlight(
    val path: List<ScreenPoint>,
    val roll: List<ScreenPoint>,
    val targetLine: List<ScreenPoint>,
    val guides: List<List<ScreenPoint>>,
    val markers: List<FlightMarker>,
) {
    /** Where the ball is [progress] (0 at the tee, 1 at landing) through its flight. */
    fun ballAt(progress: Float): ScreenPoint {
        if (path.size < 2) return path.firstOrNull() ?: ScreenPoint(0f, 0f)
        val position = progress.coerceIn(0f, 1f) * (path.size - 1)
        val index = position.toInt().coerceIn(0, path.size - 2)
        val start = path[index]
        val end = path[index + 1]
        val fraction = position - index
        return ScreenPoint(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
    }
}

/**
 * Several flights from overhead in one frame (a club's shot pattern): one point list per flight,
 * tee to landing, plus each one's resting point after the roll.
 */
data class ProjectedPattern(
    val flights: List<List<ScreenPoint>>,
    val rests: List<ScreenPoint>,
    val targetLine: List<ScreenPoint>,
    val guides: List<List<ScreenPoint>>,
    val markers: List<FlightMarker>,
)

/**
 * Projects a [ShotFlightPath] into a `width` × `height` box for one [FlightView] (pure, no
 * platform types). [FlightView.BEHIND] is a perspective camera low behind the tee that orbits the
 * flight's middle by `orbitDegrees` (clamped to ±[MAX_ORBIT_DEGREES]) and is scaled uniformly, so
 * the picture stays true. [FlightView.TOP] and [FlightView.SIDE] stretch each axis to fill the box,
 * with at least ±[MIN_TOP_HALF_WIDTH_YARDS] across and [MIN_SIDE_HEIGHT_YARDS] up, so a straight
 * shot looks straight and a curve still reads on a small card.
 */
object FlightPathProjector {
    const val MAX_ORBIT_DEGREES = 60f
    const val MIN_TOP_HALF_WIDTH_YARDS = 25.0
    const val MIN_SIDE_HEIGHT_YARDS = 12.0

    @Suppress("LongParameterList")
    fun project(
        path: ShotFlightPath,
        view: FlightView,
        width: Float,
        height: Float,
        orbitDegrees: Float = 0f,
        padding: Float = DEFAULT_PADDING,
    ): ProjectedFlight {
        val world = World.of(path)
        val box = Box(width, height, padding)
        return when (view) {
            // From behind, the guides' near ends and the side view's ceiling sit beside or above the
            // low camera and would shrink the flight to a speck; they're drawn, clipped, not framed.
            FlightView.BEHIND -> {
                world.fit(
                    box,
                    uniform = true,
                    frameGround = false,
                    project = behind(world, orbitDegrees),
                )
            }

            FlightView.TOP -> {
                world.fit(box, uniform = false, frameGround = true) { Pair(it.x, it.z) }
            }

            FlightView.SIDE -> {
                world.fit(box, uniform = false, frameGround = true) { Pair(it.z, it.y) }
            }
        }
    }

    /**
     * Several flights from overhead in one shared frame (the bag's shot pattern), so their curves
     * compare truthfully; `null` without flights. Framed like [FlightView.TOP].
     */
    fun projectPattern(
        paths: List<ShotFlightPath>,
        width: Float,
        height: Float,
        padding: Float = DEFAULT_PADDING,
    ): ProjectedPattern? {
        val longest = paths.maxByOrNull { max(it.landing.z, it.rest.z) } ?: return null
        val world = World.of(longest, paths)
        val toScreen =
            world.mapper(
                Box(width, height, padding),
                uniform = false,
                frameGround = true,
            ) { Pair(it.x, it.z) }
        return ProjectedPattern(
            flights = paths.map { path -> path.points.map(toScreen) },
            rests = paths.map { toScreen(it.rest) },
            targetLine = world.targetLine.map(toScreen),
            guides = world.guides.map { it.map(toScreen) },
            markers = world.markers.map { (point, yards) -> FlightMarker(toScreen(point), yards) },
        )
    }

    private class Box(
        val width: Float,
        val height: Float,
        val padding: Float,
    )

    /** The 3D scene in yards: the flight plus its ground references. */
    private class World(
        val path: List<Vec3>,
        val roll: List<Vec3>,
        val targetLine: List<Vec3>,
        val guides: List<List<Vec3>>,
        val markers: List<Pair<Vec3, Int>>,
        /** A point only there to set the frame: the side view's minimum height. */
        val ceiling: Vec3,
        /** Other flights sharing the frame (a shot pattern). */
        val others: List<Vec3> = emptyList(),
    ) {
        val apex: Double get() = path.maxOf { it.y }
        val landing: Vec3 get() = path.last()

        fun fit(
            box: Box,
            uniform: Boolean,
            frameGround: Boolean,
            project: (Vec3) -> Pair<Double, Double>,
        ): ProjectedFlight {
            val toScreen = mapper(box, uniform, frameGround, project)
            return ProjectedFlight(
                path = path.map(toScreen),
                roll = roll.map(toScreen),
                targetLine = targetLine.map(toScreen),
                guides = guides.map { it.map(toScreen) },
                markers = spaced(markers.map { (point, yards) -> FlightMarker(toScreen(point), yards) }),
            )
        }

        /** Maps a world point into [box], framing the flight (and the ground when [frameGround]). */
        fun mapper(
            box: Box,
            uniform: Boolean,
            frameGround: Boolean,
            project: (Vec3) -> Pair<Double, Double>,
        ): (Vec3) -> ScreenPoint {
            val framed =
                path + roll + targetLine + others + if (frameGround) guides.flatten() + ceiling else emptyList()
            val projected = framed.map(project)
            val minU = projected.minOf { it.first }
            val minV = projected.minOf { it.second }
            val spanU = max(projected.maxOf { it.first } - minU, EPSILON)
            val spanV = max(projected.maxOf { it.second } - minV, EPSILON)
            val innerWidth = max(box.width - 2 * box.padding, 1f)
            val innerHeight = max(box.height - 2 * box.padding, 1f)
            val fitU = innerWidth / spanU
            val fitV = innerHeight / spanV
            val scaleU = if (uniform) min(fitU, fitV) else fitU
            val scaleV = if (uniform) min(fitU, fitV) else fitV
            val offsetU = box.padding + (innerWidth - spanU * scaleU) / 2
            val offsetV = box.padding + (innerHeight - spanV * scaleV) / 2
            return { point: Vec3 ->
                val (u, v) = project(point)
                ScreenPoint(
                    x = (offsetU + (u - minU) * scaleU).toFloat(),
                    y = (box.height - offsetV - (v - minV) * scaleV).toFloat(),
                )
            }
        }

        /** Drops markers that would crowd the last one kept (perspective bunches them at the horizon). */
        private fun spaced(markers: List<FlightMarker>): List<FlightMarker> {
            val kept = mutableListOf<FlightMarker>()
            markers.forEach { marker ->
                val last = kept.lastOrNull()?.position
                val gap = last?.let { hypot(marker.position.x - it.x, marker.position.y - it.y) }
                if (gap == null || gap >= MIN_MARKER_GAP) kept += marker
            }
            return kept
        }

        companion object {
            /** [path]'s scene, framed to fit [all] flights (itself included) when there are several. */
            fun of(
                path: ShotFlightPath,
                all: List<ShotFlightPath> = listOf(path),
            ): World {
                val landing = path.landing
                val farthest = max(all.maxOf { max(it.landing.z, it.rest.z) }, 1.0)
                val length = farthest * TARGET_LINE_OVERSHOOT
                val step = if (farthest >= LONG_SHOT_YARDS) LONG_MARKER_STEP else SHORT_MARKER_STEP
                val markers = (step..length.toInt() step step).map { Vec3(0.0, 0.0, it.toDouble()) to it }
                val lateral = all.maxOf { flight -> (flight.points + flight.rest).maxOf { abs(it.x) } }
                val halfWidth = max(MIN_TOP_HALF_WIDTH_YARDS, lateral * LATERAL_MARGIN)
                return World(
                    path = path.points,
                    roll = if (path.rest == landing) emptyList() else listOf(landing, path.rest),
                    targetLine = listOf(Vec3.ZERO, Vec3(0.0, 0.0, length)),
                    guides =
                        listOf(
                            listOf(Vec3(-halfWidth, 0.0, 0.0), Vec3(-halfWidth, 0.0, length)),
                            listOf(Vec3(halfWidth, 0.0, 0.0), Vec3(halfWidth, 0.0, length)),
                        ),
                    markers = markers,
                    ceiling = Vec3(0.0, max(MIN_SIDE_HEIGHT_YARDS, path.apexYards * APEX_MARGIN), 0.0),
                    others = all.filter { it !== path }.flatMap { it.points + it.rest },
                )
            }
        }
    }

    /** A pinhole camera low behind the tee, orbited about the flight's middle by [orbitDegrees]. */
    private fun behind(
        world: World,
        orbitDegrees: Float,
    ): (Vec3) -> Pair<Double, Double> {
        val lookAt = Vec3(0.0, world.apex * LOOK_AT_APEX_FRACTION, world.landing.z * LOOK_AT_CARRY_FRACTION)
        val home = Vec3(0.0, CAMERA_HEIGHT_YARDS, -CAMERA_BACK_YARDS) - lookAt
        val yaw = orbitDegrees.coerceIn(-MAX_ORBIT_DEGREES, MAX_ORBIT_DEGREES) * PI / HALF_TURN_DEGREES
        val camera =
            lookAt + Vec3(home.x * cos(yaw) + home.z * sin(yaw), home.y, -home.x * sin(yaw) + home.z * cos(yaw))
        val forward = normalized(lookAt - camera)
        val right = normalized(Vec3(0.0, 1.0, 0.0) cross forward)
        val up = forward cross right
        return { point ->
            val delta = point - camera
            val depth = max(dot(delta, forward), NEAR_PLANE_YARDS)
            Pair(dot(delta, right) / depth, dot(delta, up) / depth)
        }
    }

    private fun dot(
        a: Vec3,
        b: Vec3,
    ): Double = a.x * b.x + a.y * b.y + a.z * b.z

    private fun normalized(vector: Vec3): Vec3 = vector / max(vector.length(), EPSILON)

    private const val DEFAULT_PADDING = 16f
    private const val MIN_MARKER_GAP = 18f
    private const val EPSILON = 1e-6
    private const val HALF_TURN_DEGREES = 180.0
    private const val TARGET_LINE_OVERSHOOT = 1.08
    private const val LONG_SHOT_YARDS = 150.0
    private const val LONG_MARKER_STEP = 50
    private const val SHORT_MARKER_STEP = 25
    private const val LATERAL_MARGIN = 1.3
    private const val APEX_MARGIN = 1.2
    private const val LOOK_AT_APEX_FRACTION = 0.3
    private const val LOOK_AT_CARRY_FRACTION = 0.55
    private const val CAMERA_HEIGHT_YARDS = 3.0
    private const val CAMERA_BACK_YARDS = 12.0
    private const val NEAR_PLANE_YARDS = 1.0
}
