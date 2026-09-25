// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The follow-ball camera (plan R7a). Every property is checked on real simulated flights (a
 * driver, a slice, a high wedge and a low punch) rather than a hand-made arc, sampled at 120 Hz of
 * flight time: the planner is analytic, so these samples are exactly what any renderer will see.
 */
class FollowCameraPlannerTest {
    private val planner = FollowCameraPlanner()
    private val fixed = RangeCameraPlanner().pose

    private val flights: List<FlightTrajectory> =
        listOf(
            makeDrivingRangeShot(eventId = "B0D91F0A-7950-4D7E-9DD5-000000000001"),
            makeDrivingRangeShot(
                eventId = "B0D91F0A-7950-4D7E-9DD5-000000000002",
                horizontalLaunch = 4.0,
                spinAxis = 25.0,
            ),
            makeDrivingRangeShot(
                eventId = "B0D91F0A-7950-4D7E-9DD5-000000000003",
                club = "pw",
                ballSpeedMph = 95.0,
                carryYards = 120.0,
                launchAngle = 30.0,
                spinRpm = 8_500.0,
                spinAxis = 0.0,
            ),
            makeDrivingRangeShot(
                eventId = "B0D91F0A-7950-4D7E-9DD5-000000000004",
                club = "7-iron",
                ballSpeedMph = 110.0,
                carryYards = 140.0,
                launchAngle = 6.0,
                spinRpm = 3_000.0,
                spinAxis = -8.0,
            ),
        ).map { BallFlightSimulator().simulate(FlightInputResolver().resolve(it)) }

    @Test
    fun atLaunchThePoseIsTheFixedTeePose() {
        for (flight in flights) {
            val pose = planner.pose(flight, timeSeconds = 0.0, landedElapsedSeconds = null)

            assertThat(distance(pose.position, fixed.position)).isLessThan(EPSILON)
            assertThat(distance(pose.target, fixed.target)).isLessThan(EPSILON)
            assertThat(pose.verticalFovDegrees).isEqualTo(fixed.verticalFovDegrees)
        }
    }

    @Test
    fun consecutive120HzFramesNeverJump() {
        for (flight in flights) {
            var previous = planner.pose(flight, 0.0, null)
            var maxPositionStep = 0.0
            var maxTargetStep = 0.0
            for (pose in samples(flight).drop(1)) {
                maxPositionStep = max(maxPositionStep, distance(pose.position, previous.position))
                maxTargetStep = max(maxTargetStep, distance(pose.target, previous.target))
                previous = pose
            }

            // The ball itself covers up to ~0.6 m per 120 Hz frame at launch.
            assertThat(maxPositionStep, flight.eventId).isLessThan(MAX_POSITION_STEP_METERS)
            assertThat(maxTargetStep, flight.eventId).isLessThan(MAX_TARGET_STEP_METERS)
        }
    }

    @Test
    fun landingHandsOverToTheSettleWithoutAJump() {
        for (flight in flights) {
            val lastFlightPose = planner.pose(flight, flight.flightTime, null)
            val firstLandedPose = planner.pose(flight, flight.flightTime, landedElapsedSeconds = 0.0)

            assertThat(distance(lastFlightPose.position, firstLandedPose.position)).isLessThan(EPSILON)
            assertThat(distance(lastFlightPose.target, firstLandedPose.target)).isLessThan(EPSILON)
        }
    }

    @Test
    fun theCameraStaysAboveTheGround() {
        for (flight in flights) {
            for (pose in samples(flight)) {
                assertThat(pose.position.y, flight.eventId).isGreaterThan(MIN_CAMERA_HEIGHT_METERS)
            }
        }
    }

    @Test
    fun theSettledCameraLooksDownAtTheLandingSpotFromAbove() {
        for (flight in flights) {
            val settled = planner.pose(flight, flight.flightTime, landedElapsedSeconds = HOLD_SECONDS)
            val landing = sceneLanding(flight)

            assertThat(distance(settled.target, landing), flight.eventId).isLessThan(1.0)
            assertThat(settled.position.y, flight.eventId).isGreaterThan(SETTLED_MIN_HEIGHT_METERS)
            assertThat(settled.position.y, flight.eventId).isLessThan(SETTLED_MAX_HEIGHT_METERS)
            // It holds for the rest of the dwell.
            assertThat(planner.pose(flight, flight.flightTime, landedElapsedSeconds = 10.0)).isEqualTo(settled)
        }
    }

    @Test
    fun theSettledCameraSitsBehindTheLandingSpotLookingAlongTheFlight() {
        for (flight in flights) {
            val settled = planner.pose(flight, flight.flightTime, landedElapsedSeconds = HOLD_SECONDS)
            val heading = landingHeading(flight)
            val look = horizontal(settled.target - settled.position)

            // Yaw: within ~15° of the ball's heading as it came down, not across the range.
            assertThat(angleDegrees(look, heading), flight.eventId).isLessThan(MAX_SETTLE_YAW_DEGREES)
            // Behind the landing spot (on the tee side of it), not beside or past it.
            val back = sceneLanding(flight) - settled.position
            assertThat(back.x * heading.x + back.z * heading.z, flight.eventId).isGreaterThan(MIN_SETBACK_METERS)
        }
    }

    @Test
    fun theSettleNeverSwingsTheView() {
        for (flight in flights) {
            var previous = planner.pose(flight, flight.flightTime, landedElapsedSeconds = 0.0)
            var maxTurn = 0.0
            for (step in 1..(HOLD_SECONDS * SAMPLE_HZ).toInt()) {
                val pose = planner.pose(flight, flight.flightTime, step / SAMPLE_HZ)
                maxTurn = max(maxTurn, angleDegrees(pose.target - pose.position, previous.target - previous.position))
                previous = pose
            }

            assertThat(maxTurn, flight.eventId).isLessThan(MAX_SETTLE_TURN_PER_FRAME_DEGREES)
        }
    }

    @Test
    fun theSettledViewStaysBehindAndAboveTheLandingSpotWhereverTheMarkersAre() {
        // Landings beside, just past and just short of every marker (the pull-in path included).
        val markers = RangeSceneDescription.standard(treeCount = 0).markerScenePositions
        for (marker in markers) {
            for (offset in listOf(-24.0, -14.0, -6.0, 0.0, 6.0, 14.0)) {
                val landing = Vec3(0.0, 0.0, marker.z - offset)
                val settled = planner.settledPose(straightFlightTo(landing))
                val label = "marker ${marker.z}, offset $offset"

                assertThat(settled.position.y, label).isGreaterThan(SETTLED_MIN_HEIGHT_METERS)
                assertThat(settled.position.y, label).isLessThan(SETTLED_MAX_HEIGHT_METERS)
                assertThat(settled.position.z - landing.z, label).isGreaterThan(MIN_SETBACK_METERS)
                assertThat(distance(settled.target, landing), label).isLessThan(EPSILON)
            }
        }
    }

    @Test
    fun theSameInputsAlwaysGiveTheSamePose() {
        val flight = flights.first()
        val again = FollowCameraPlanner()

        for (step in 0..240) {
            val time = step / SAMPLE_HZ
            assertThat(again.pose(flight, time, null)).isEqualTo(planner.pose(flight, time, null))
        }
        assertThat(again.pose(flight, flight.flightTime, 0.4)).isEqualTo(planner.pose(flight, flight.flightTime, 0.4))
    }

    @Test
    fun anEmptyTrajectoryFallsBackToTheFixedPose() {
        val empty = FlightTrajectory(eventId = "empty", points = emptyList(), provenance = FlightInputProvenance())

        assertThat(planner.pose(empty, 1.0, null)).isEqualTo(fixed)
        assertThat(planner.pose(empty, 1.0, 1.0)).isEqualTo(fixed)
    }

    /** The flight at 120 Hz of flight time, then the landed settle at 120 Hz until it holds. */
    private fun samples(flight: FlightTrajectory): List<RangeCameraPose> {
        val flying = (0..(flight.flightTime * SAMPLE_HZ).toInt()).map { planner.pose(flight, it / SAMPLE_HZ, null) }
        val landed =
            (0..(HOLD_SECONDS * SAMPLE_HZ).toInt()).map {
                planner.pose(
                    flight,
                    flight.flightTime,
                    it / SAMPLE_HZ,
                )
            }
        return flying + planner.pose(flight, flight.flightTime, null) + landed
    }

    /** The ball's horizontal heading in scene space as it lands. */
    private fun landingHeading(flight: FlightTrajectory): Vec3 {
        val velocity = flight.points.last().velocityMetersPerSecond
        return horizontal(Vec3(velocity.x, 0.0, -velocity.z))
    }

    /** A two-point flight straight downrange that lands on the scene-space point [landing]. */
    private fun straightFlightTo(landing: Vec3): FlightTrajectory =
        FlightTrajectory(
            eventId = "straight",
            points =
                listOf(
                    FlightPoint(0.0, Vec3.ZERO, Vec3(0.0, 20.0, 40.0)),
                    FlightPoint(5.0, Vec3(landing.x, 0.0, -landing.z), Vec3(0.0, -15.0, 20.0)),
                ),
            provenance = FlightInputProvenance(),
        )

    private fun horizontal(vector: Vec3): Vec3 {
        val length = sqrt(vector.x * vector.x + vector.z * vector.z)
        return Vec3(vector.x / length, 0.0, vector.z / length)
    }

    private fun angleDegrees(
        a: Vec3,
        b: Vec3,
    ): Double {
        val cosine = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length() * b.length())
        return acos(cosine.coerceIn(-1.0, 1.0)) * DEGREES_PER_HALF_TURN / PI
    }

    /** Scene space (downrange is −z), like [RangeCameraPlanner]'s poses. */
    private fun sceneLanding(flight: FlightTrajectory): Vec3 {
        val last = flight.points.last().positionMeters
        return Vec3(last.x, 0.0, -last.z)
    }

    private fun distance(
        a: Vec3,
        b: Vec3,
    ): Double = (a - b).length()

    private companion object {
        const val SAMPLE_HZ = 120.0
        const val EPSILON = 1e-6
        const val HOLD_SECONDS = 1.25
        const val MAX_POSITION_STEP_METERS = 1.2
        const val MAX_TARGET_STEP_METERS = 2.5
        const val MIN_CAMERA_HEIGHT_METERS = 0.5
        const val SETTLED_MIN_HEIGHT_METERS = 7.5
        const val SETTLED_MAX_HEIGHT_METERS = 15.0
        const val MAX_SETTLE_YAW_DEGREES = 15.0
        const val MIN_SETBACK_METERS = 8.0
        const val MAX_SETTLE_TURN_PER_FRAME_DEGREES = 0.75
        const val DEGREES_PER_HALF_TURN = 180.0
    }
}
