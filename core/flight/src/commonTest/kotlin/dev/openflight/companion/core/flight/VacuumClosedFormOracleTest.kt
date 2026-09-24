// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * A new test (not ported from the reference): the vacuum configuration strips out drag and
 * lift, so `BallFlightSimulator`'s RK4 integration should reproduce textbook closed-form
 * projectile motion for *any* launch condition, not just the single case
 * `BallFlightSimulatorTest.vacuumTrajectoryMatchesClosedFormBallistics` ported from
 * `BallFlightSimulatorTests.swift`. This is an independent oracle: the expected values below are
 * derived directly from `range = v^2 * sin(2*theta) / g`, `apex = (v*sin(theta))^2 / (2*g)` and
 * `time = 2 * v * sin(theta) / g`, not from reading the simulator's own implementation.
 */
class VacuumClosedFormOracleTest {
    @Test
    fun vacuumTrajectoryMatchesClosedFormRangeApexAndTimeAcrossLaunchConditions() {
        val cases =
            listOf(
                LaunchCondition(speed = 30.0, angleDegrees = 15.0),
                LaunchCondition(speed = 45.0, angleDegrees = 25.0),
                LaunchCondition(speed = 60.0, angleDegrees = 35.0),
                LaunchCondition(speed = 70.0, angleDegrees = 45.0),
                LaunchCondition(speed = 55.0, angleDegrees = 50.0),
            )
        val simulator = BallFlightSimulator(BallFlightSimulator.Configuration.vacuum)

        for (case in cases) {
            val angleRadians = case.angleDegrees * PI / 180.0
            val verticalSpeed = case.speed * sin(angleRadians)
            val expectedTime = 2 * verticalSpeed / GRAVITY
            val expectedRange = case.speed * case.speed * sin(2 * angleRadians) / GRAVITY
            val expectedApex = (verticalSpeed * verticalSpeed) / (2 * GRAVITY)

            val input =
                FlightInput(
                    eventId = "22222222-3333-4444-5555-666666666666",
                    ballSpeedMetersPerSecond = case.speed,
                    launchAngleDegrees = case.angleDegrees,
                    horizontalLaunchDegrees = 0.0,
                    spinRpm = 0.0,
                    spinAxisDegrees = 0.0,
                    targetCarryMeters = expectedRange,
                    provenance = FlightInputProvenance(),
                )
            val trajectory = simulator.simulate(input)

            assertThat(trajectory.flightTime, "flightTime@${case.angleDegrees}").isCloseTo(expectedTime, 0.02)
            assertThat(trajectory.carryMeters, "carryMeters@${case.angleDegrees}").isCloseTo(expectedRange, 0.6)
            assertThat(trajectory.apexMeters, "apexMeters@${case.angleDegrees}").isCloseTo(expectedApex, 0.3)
        }
    }

    private data class LaunchCondition(
        val speed: Double,
        val angleDegrees: Double,
    )

    private companion object {
        const val GRAVITY = 9.80665
    }
}
