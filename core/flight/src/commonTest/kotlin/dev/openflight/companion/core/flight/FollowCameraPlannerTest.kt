// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.math.max
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
        const val SETTLED_MIN_HEIGHT_METERS = 12.0
        const val SETTLED_MAX_HEIGHT_METERS = 20.0
    }
}
