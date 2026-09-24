// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The follow-ball camera (plan R7a): a pure function from a flight and a time to a
 * [RangeCameraPose], so every platform, every replay and every test sees the same pose for the
 * same instant. There are no per-frame springs; every stage is evaluated from the trajectory and
 * blended with smoothstep eases.
 *
 * Poses are in **scene space**, like [RangeCameraPlanner]: y is up and downrange is **−z**. The
 * simulator's downrange axis is +z, so trajectory points are mirrored in z first (the reference's
 * `scenePosition(_:)`).
 *
 * 1. **Launch.** At t = 0 the pose *is* [fixedPose], then it eases over to the chase pose, so
 *    switching between FIXED and FOLLOW never cuts at the start of a shot.
 * 2. **Flight.** The camera chases from behind the ball's direction of travel and above it. The
 *    offset grows with the ball's horizontal speed and height, and the target leads the ball.
 * 3. **Descent** (the last [DESCENT_FRACTION] of the flight). The target eases toward the landing
 *    spot while the camera rises, pulls back and widens its field of view, so the ball and the
 *    landing area are both in frame.
 * 4. **Landed.** Over [SETTLE_SECONDS] the camera settles into a raised view looking down at the
 *    landing spot, lined up so the nearest yardage marker is in frame, and then holds.
 *
 * @param fixedPose the tee camera to start from (and to fall back to for an empty trajectory).
 * @param markers the yardage markers' ground positions in scene space.
 */
class FollowCameraPlanner(
    private val fixedPose: RangeCameraPose = RangeCameraPlanner().pose,
    private val markers: List<Vec3> = RangeSceneDescription.standard(treeCount = 0).markerScenePositions,
) {
    /**
     * The pose at [timeSeconds] of flight time (0 to [FlightTrajectory.flightTime], clamped), or,
     * once the ball has landed, [landedElapsedSeconds] after the landing (then [timeSeconds] is
     * ignored). Renderers that play a flight over its playback duration pass
     * `progress * trajectory.flightTime`.
     */
    fun pose(
        trajectory: FlightTrajectory,
        timeSeconds: Double,
        landedElapsedSeconds: Double?,
    ): RangeCameraPose =
        if (landedElapsedSeconds == null) {
            flightPose(trajectory, timeSeconds)
        } else {
            landedPose(trajectory, landedElapsedSeconds)
        }

    /** The pose while the ball is in the air, at [timeSeconds] of flight time. */
    fun flightPose(
        trajectory: FlightTrajectory,
        timeSeconds: Double,
    ): RangeCameraPose {
        val duration = trajectory.flightTime
        val point = trajectory.point(timeSeconds.coerceIn(0.0, duration))
        if (point == null || duration <= 0.0) return fixedPose
        val time = point.time.coerceIn(0.0, duration)

        val ball = scene(point.positionMeters)
        val velocity = scene(point.velocityMetersPerSecond)
        val landing = landing(trajectory)
        val carryDirection = horizontalDirection(landing, fallback = DOWNRANGE)
        val horizontalSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
        val heading = horizontalDirection(velocity, fallback = carryDirection)

        // Descent: 0 until the last DESCENT_FRACTION of the flight, then eases to 1 at landing.
        val descent = smoothstep((time / duration - (1 - DESCENT_FRACTION)) / DESCENT_FRACTION)
        val back = CHASE_BACK_METERS + CHASE_BACK_PER_SPEED * horizontalSpeed + DESCENT_EXTRA_BACK_METERS * descent
        val above =
            CHASE_ABOVE_METERS + CHASE_ABOVE_PER_HEIGHT * ball.y + CHASE_ABOVE_PER_SPEED * horizontalSpeed +
                DESCENT_EXTRA_ABOVE_METERS * descent
        val chasePosition = Vec3(ball.x - heading.x * back, ball.y + above, ball.z - heading.z * back)
        val leading = ball + velocity * LEAD_SECONDS
        val chaseTarget = lerp(leading, landing, descent * DESCENT_TARGET_WEIGHT)
        val chaseFov = lerp(DEFAULT_FOV, DESCENT_FOV_DEGREES, descent)

        // Launch: ease from the fixed tee camera to the chase camera. The swing from the fixed
        // target 150 m out to the ball takes at least LAUNCH_MIN_SECONDS (unless the flight is too
        // short for that), so it never whips round.
        val launchSeconds =
            (duration * LAUNCH_FRACTION).coerceIn(
                minOf(LAUNCH_MIN_SECONDS, duration * LAUNCH_MAX_FRACTION),
                LAUNCH_MAX_SECONDS,
            )
        val launch = smoothstep(time / launchSeconds)
        return RangeCameraPose(
            position = lerp(fixedPose.position, chasePosition, launch),
            target = lerp(fixedPose.target, chaseTarget, launch),
            verticalFovDegrees = lerp(fixedPose.verticalFovDegrees, chaseFov, launch),
        )
    }

    /** The pose [elapsedSeconds] after landing: the end of the flight easing into [settledPose]. */
    fun landedPose(
        trajectory: FlightTrajectory,
        elapsedSeconds: Double,
    ): RangeCameraPose {
        if (trajectory.points.isEmpty() || trajectory.flightTime <= 0.0) return fixedPose
        val start = flightPose(trajectory, trajectory.flightTime)
        val end = settledPose(trajectory)
        val settle = smoothstep(elapsedSeconds / SETTLE_SECONDS)
        return RangeCameraPose(
            position = lerp(start.position, end.position, settle),
            target = lerp(start.target, end.target, settle),
            verticalFovDegrees = lerp(start.verticalFovDegrees, end.verticalFovDegrees, settle),
        )
    }

    /**
     * Where the camera comes to rest: [SETTLED_HEIGHT_METERS] up, looking down at the landing spot
     * along the line from the nearest yardage marker, so that marker is in frame too. The camera
     * always stays on the tee side of the landing spot (the line is turned at most
     * [MAX_SETTLE_TURN_DEGREES] away from the carry direction), and backs off far enough to keep a
     * marker that is short of the landing spot above the bottom of the frame.
     */
    fun settledPose(trajectory: FlightTrajectory): RangeCameraPose {
        if (trajectory.points.isEmpty() || trajectory.flightTime <= 0.0) return fixedPose
        val landing = landing(trajectory)
        val carryDirection = horizontalDirection(landing, fallback = DOWNRANGE)
        val marker = markers.minByOrNull { horizontalDistance(it, landing) }
        val toMarker = marker?.let { it - landing } ?: Vec3.ZERO
        val markerDistance = horizontalDistance(toMarker, Vec3.ZERO)
        val markerIsShort = toMarker.x * carryDirection.x + toMarker.z * carryDirection.z < 0

        val look =
            when {
                markerDistance < MARKER_ALIGN_MIN_METERS -> carryDirection
                markerIsShort -> turnToward(carryDirection, -toMarker)
                else -> turnToward(carryDirection, toMarker)
            }
        val setback =
            if (markerIsShort) {
                maxOf(SETTLED_SETBACK_METERS, markerDistance + SHORT_MARKER_CLEARANCE_METERS)
            } else {
                SETTLED_SETBACK_METERS
            }
        return RangeCameraPose(
            position = Vec3(landing.x - look.x * setback, SETTLED_HEIGHT_METERS, landing.z - look.z * setback),
            target = landing,
            verticalFovDegrees = SETTLED_FOV_DEGREES,
        )
    }

    /** [desired]'s horizontal direction, turned toward from [base] by at most [MAX_SETTLE_TURN_DEGREES]. */
    private fun turnToward(
        base: Vec3,
        desired: Vec3,
    ): Vec3 {
        val wanted = horizontalDirection(desired, fallback = base)
        val cosine = base.x * wanted.x + base.z * wanted.z
        if (cosine >= cos(MAX_SETTLE_TURN_RADIANS)) return wanted
        // Turn `base` by the maximum angle, toward the side `wanted` is on.
        val side = if (base.x * wanted.z - base.z * wanted.x >= 0) 1.0 else -1.0
        val angle = MAX_SETTLE_TURN_RADIANS * side
        return Vec3(
            base.x * cos(angle) - base.z * sin(angle),
            0.0,
            base.x * sin(angle) + base.z * cos(angle),
        )
    }

    private fun landing(trajectory: FlightTrajectory): Vec3 {
        val last = trajectory.points.last().positionMeters
        return Vec3(last.x, 0.0, -last.z)
    }

    companion object {
        /** How long the landed camera takes to settle; shorter than the 1.25 s landing dwell. */
        const val SETTLE_SECONDS = 0.9

        /** The share of the flight, at its end, spent framing the landing. */
        const val DESCENT_FRACTION = 0.2

        private const val DEFAULT_FOV = RangeCameraPose.DEFAULT_VERTICAL_FOV_DEGREES
        private val DOWNRANGE = Vec3(0.0, 0.0, -1.0)

        private const val LAUNCH_FRACTION = 0.25
        private const val LAUNCH_MAX_FRACTION = 0.6
        private const val LAUNCH_MIN_SECONDS = 1.0
        private const val LAUNCH_MAX_SECONDS = 1.2
        private const val CHASE_BACK_METERS = 10.0
        private const val CHASE_BACK_PER_SPEED = 0.14
        private const val CHASE_ABOVE_METERS = 2.0
        private const val CHASE_ABOVE_PER_HEIGHT = 0.3
        private const val CHASE_ABOVE_PER_SPEED = 0.04
        private const val LEAD_SECONDS = 0.18

        private const val DESCENT_EXTRA_BACK_METERS = 6.0
        private const val DESCENT_EXTRA_ABOVE_METERS = 6.0
        private const val DESCENT_TARGET_WEIGHT = 0.5
        private const val DESCENT_FOV_DEGREES = 66.0

        private const val SETTLED_HEIGHT_METERS = 16.0
        private const val SETTLED_SETBACK_METERS = 25.0
        private const val SHORT_MARKER_CLEARANCE_METERS = 18.0
        private const val SETTLED_FOV_DEGREES = 62.0
        private const val MARKER_ALIGN_MIN_METERS = 4.0
        private const val MAX_SETTLE_TURN_DEGREES = 50.0
        private const val MAX_SETTLE_TURN_RADIANS = MAX_SETTLE_TURN_DEGREES * PI / 180.0

        /** Simulator space (+z downrange) to scene space (−z downrange). */
        private fun scene(vector: Vec3): Vec3 = Vec3(vector.x, vector.y, -vector.z)

        private fun horizontalDirection(
            vector: Vec3,
            fallback: Vec3,
        ): Vec3 {
            val length = sqrt(vector.x * vector.x + vector.z * vector.z)
            return if (length < MIN_DIRECTION_LENGTH) fallback else Vec3(vector.x / length, 0.0, vector.z / length)
        }

        private fun horizontalDistance(
            a: Vec3,
            b: Vec3,
        ): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

        private fun lerp(
            from: Vec3,
            to: Vec3,
            amount: Double,
        ): Vec3 = from + (to - from) * amount

        private fun lerp(
            from: Double,
            to: Double,
            amount: Double,
        ): Double = from + (to - from) * amount

        /** 0 below 0, 1 above 1, and the C1-continuous `3x² − 2x³` in between. */
        private fun smoothstep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (SMOOTHSTEP_SQUARE - SMOOTHSTEP_CUBE * t)
        }

        private const val MIN_DIRECTION_LENGTH = 1e-6
        private const val SMOOTHSTEP_SQUARE = 3.0
        private const val SMOOTHSTEP_CUBE = 2.0
    }
}
