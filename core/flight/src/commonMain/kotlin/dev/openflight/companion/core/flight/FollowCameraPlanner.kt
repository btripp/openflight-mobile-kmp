// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
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
 * 4. **Landed.** Over [SETTLE_SECONDS] the camera settles into a raised view behind the landing
 *    spot, looking down the flight line at it with the next yardage marker beyond it in frame, and
 *    then holds (see [settledPose]).
 *
 * @param fixedPose the tee camera to start from (and to fall back to for an empty trajectory).
 * @param scene the range whose yardage markers the settled view frames (and keeps out of its foreground).
 */
class FollowCameraPlanner(
    private val fixedPose: RangeCameraPose = RangeCameraPlanner().pose,
    scene: RangeSceneDescription = RangeSceneDescription.standard(treeCount = 0),
) {
    private val markerPositions: List<Vec3> = scene.markerScenePositions
    private val markerRadii: List<Double> = scene.markers.map { it.radiusMeters }

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
     * Where the camera comes to rest (plan R7b): behind the landing spot along the ball's heading
     * as it came down, raised [SETTLED_HEIGHT_METERS] and looking down the flight line at the
     * landing spot, so the next yardage marker *beyond* it is in frame.
     *
     * - The line is turned at most [MAX_SETTLE_TURN_DEGREES] toward that next marker, so the view
     *   stays within a few degrees of where the chase camera was already looking.
     * - A marker short of the landing spot whose disc would sit between the camera and the landing
     *   spot is put behind the camera instead, by pulling the camera in (never closer than
     *   [MIN_SETTLED_SETBACK_METERS]) and lowering it with the setback so the pitch, and with it the
     *   marker beyond, stays in frame. A marker too close to the landing spot for that is kept at
     *   least [SHORT_MARKER_MIN_DISTANCE_METERS] in front of the camera by backing off instead, so it
     *   never shows up as a huge disc in the foreground.
     */
    fun settledPose(trajectory: FlightTrajectory): RangeCameraPose {
        if (trajectory.points.isEmpty() || trajectory.flightTime <= 0.0) return fixedPose
        val landing = landing(trajectory)
        val heading = landingHeading(trajectory, landing)
        val look = turnTowardNextMarker(heading, landing)

        var setback = SETTLED_SETBACK_METERS
        for (index in markerPositions.indices) {
            val offset = markerPositions[index] - landing
            val behind = -(offset.x * look.x + offset.z * look.z)
            val lateral = abs(offset.x * look.z - offset.z * look.x)
            val nearEdge = behind - markerRadii[index]
            val inForeground =
                behind > 0 && lateral < FOREGROUND_LATERAL_METERS && nearEdge < setback - FOREGROUND_GAP_METERS
            if (inForeground) {
                val pastMarker = nearEdge + FOREGROUND_GAP_METERS - 1.0
                setback =
                    if (pastMarker >= MIN_SETTLED_SETBACK_METERS) {
                        minOf(setback, pastMarker)
                    } else {
                        // Too close to the landing spot to get past: back off so it is small.
                        maxOf(setback, behind + SHORT_MARKER_MIN_DISTANCE_METERS)
                    }
            }
        }
        val height = (setback * SETTLED_HEIGHT_PER_SETBACK).coerceIn(MIN_SETTLED_HEIGHT_METERS, SETTLED_HEIGHT_METERS)
        return RangeCameraPose(
            position = Vec3(landing.x - look.x * setback, height, landing.z - look.z * setback),
            target = landing,
            verticalFovDegrees = SETTLED_FOV_DEGREES,
        )
    }

    /** The ball's horizontal direction of travel as it lands (the carry direction if it has none). */
    private fun landingHeading(
        trajectory: FlightTrajectory,
        landing: Vec3,
    ): Vec3 =
        horizontalDirection(
            scene(trajectory.points.last().velocityMetersPerSecond),
            fallback = horizontalDirection(landing, fallback = DOWNRANGE),
        )

    /** [heading] turned toward the first marker beyond [landing], by at most [MAX_SETTLE_TURN_DEGREES]. */
    private fun turnTowardNextMarker(
        heading: Vec3,
        landing: Vec3,
    ): Vec3 {
        val next =
            markerPositions
                .filter { (it.x - landing.x) * heading.x + (it.z - landing.z) * heading.z > 0 }
                .minByOrNull { (it.x - landing.x) * heading.x + (it.z - landing.z) * heading.z }
                ?: return heading
        val wanted = horizontalDirection(next - landing, fallback = heading)
        // Signed angle from heading to wanted, about +y in the x/z plane.
        val angle =
            atan2(heading.x * wanted.z - heading.z * wanted.x, heading.x * wanted.x + heading.z * wanted.z)
                .coerceIn(-MAX_SETTLE_TURN_RADIANS, MAX_SETTLE_TURN_RADIANS)
        return Vec3(
            heading.x * cos(angle) - heading.z * sin(angle),
            0.0,
            heading.x * sin(angle) + heading.z * cos(angle),
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

        private const val SETTLED_HEIGHT_METERS = 13.0
        private const val MIN_SETTLED_HEIGHT_METERS = 8.0
        private const val SETTLED_HEIGHT_PER_SETBACK = 0.55
        private const val SETTLED_SETBACK_METERS = 26.0
        private const val MIN_SETTLED_SETBACK_METERS = 10.0
        private const val SETTLED_FOV_DEGREES = 64.0

        /** A short marker's disc is out of shot once its near edge is this far in front of the camera or less. */
        private const val FOREGROUND_GAP_METERS = 3.0

        /** How far in front of the camera a short marker it can't get past is kept. */
        private const val SHORT_MARKER_MIN_DISTANCE_METERS = 32.0

        /** Markers further than this to either side of the flight line never reach the foreground. */
        private const val FOREGROUND_LATERAL_METERS = 30.0
        private const val MAX_SETTLE_TURN_DEGREES = 14.0
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
