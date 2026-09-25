// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/** Ported one-to-one from `ios/OpenFlightTests/BallFlightSimulatorTests.swift`. */
class BallFlightSimulatorTest {
    @Test
    fun vacuumTrajectoryMatchesClosedFormBallistics() {
        val speed = 50.0
        val angle = 30.0
        val input = makeInput(speed = speed, launch = angle, spin = 0.0, carry = 200.0)
        val trajectory = BallFlightSimulator(BallFlightSimulator.Configuration.vacuum).simulate(input)
        val verticalSpeed = speed * sin(angle * DEGREES_TO_RADIANS)
        val horizontalSpeed = speed * cos(angle * DEGREES_TO_RADIANS)
        val expectedTime = 2 * verticalSpeed / GRAVITY
        val expectedCarry = horizontalSpeed * expectedTime

        assertThat(trajectory.flightTime).isCloseTo(expectedTime, 0.02)
        assertThat(trajectory.carryMeters).isCloseTo(expectedCarry, 0.6)
        assertThat(
            trajectory.points
                .last()
                .positionMeters.y,
        ).isCloseTo(0.0, 0.0001)
    }

    @Test
    fun productionTrajectoryLandsAtOpenFlightCarry() {
        val input = makeInput(speed = 67.0, launch = 13.0, spin = 2_500.0, carry = 245.0)
        val trajectory = BallFlightSimulator().simulate(input)

        assertThat(trajectory.carryMeters).isCloseTo(245.0, 0.01)
        assertThat(trajectory.apexMeters).isGreaterThan(10.0)
        assertThat(trajectory.flightTime).isGreaterThan(2.0)
        assertThat(trajectory.points.all { it.positionMeters.y >= 0 }).isTrue()
    }

    @Test
    fun spinDecayIsOffByDefaultSoLandingSpinIsLaunchSpin() {
        val input = makeInput(speed = 67.0, launch = 13.0, spin = 2_500.0, carry = 245.0)

        assertThat(BallFlightSimulator.Configuration.standard.spinDecayPerSecond).isEqualTo(0.0)
        assertThat(BallFlightSimulator().simulate(input).landingSpinRpm).isEqualTo(2_500.0)
    }

    @Test
    fun conditionsSpinDecayFollowsTheExponential() {
        val input = makeInput(speed = 67.0, launch = 13.0, spin = 2_500.0, carry = 245.0)
        val trajectory = BallFlightSimulator(BallFlightSimulator.Configuration.conditions).simulate(input)
        val landingSpin = checkNotNull(trajectory.landingSpinRpm)

        assertThat(landingSpin).isCloseTo(2_500.0 * kotlin.math.exp(-0.04 * trajectory.flightTime), 1.0)
        assertThat(landingSpin).isLessThan(2_500.0)
    }

    @Test
    fun dragReducesUnconstrainedCarry() {
        val aerodynamicConfiguration = BallFlightSimulator.Configuration.standard.copy(constrainToTargetCarry = false)
        val input = makeInput(speed = 60.0, launch = 14.0, spin = 0.0, carry = 240.0)
        val aerodynamic = BallFlightSimulator(aerodynamicConfiguration).simulate(input)
        val vacuum = BallFlightSimulator(BallFlightSimulator.Configuration.vacuum).simulate(input)

        assertThat(aerodynamic.carryMeters).isLessThan(vacuum.carryMeters)
    }

    @Test
    fun backspinProducesMoreLiftThanNoSpin() {
        val configuration = BallFlightSimulator.Configuration.standard.copy(constrainToTargetCarry = false)
        val simulator = BallFlightSimulator(configuration)
        val noSpin = simulator.simulate(makeInput(speed = 62.0, launch = 12.0, spin = 0.0, carry = 230.0))
        val backspin = simulator.simulate(makeInput(speed = 62.0, launch = 12.0, spin = 3_000.0, carry = 230.0))

        assertThat(backspin.apexMeters).isGreaterThan(noSpin.apexMeters)
        assertThat(backspin.flightTime).isGreaterThan(noSpin.flightTime)
    }

    @Test
    fun spinAxisControlsCurveDirection() {
        val configuration = BallFlightSimulator.Configuration.standard.copy(constrainToTargetCarry = false)
        val simulator = BallFlightSimulator(configuration)
        val fade = simulator.simulate(makeInput(spinAxis = 18.0))
        val draw = simulator.simulate(makeInput(spinAxis = -18.0))

        assertThat(fade.lateralMeters).isGreaterThan(0.0)
        assertThat(draw.lateralMeters).isLessThan(0.0)
        assertThat(abs(fade.lateralMeters)).isCloseTo(abs(draw.lateralMeters), 0.2)
    }

    @Test
    fun trajectorySamplingInterpolatesBetweenFrames() {
        val trajectory = BallFlightSimulator(BallFlightSimulator.Configuration.vacuum).simulate(makeInput())
        val time = trajectory.flightTime * 0.5
        val point = trajectory.point(at = time)

        assertThat(point).isNotNull()
        assertThat(point?.time ?: Double.NaN).isCloseTo(time, 0.0001)
        assertThat(point?.positionMeters?.y ?: 0.0).isGreaterThan(0.0)
    }

    @Test
    fun integrationConvergesAcrossReasonableTimeSteps() {
        val coarseConfiguration =
            BallFlightSimulator.Configuration.standard.copy(timeStep = 1.0 / 60.0, constrainToTargetCarry = false)
        val fineConfiguration = coarseConfiguration.copy(timeStep = 1.0 / 240.0)
        val input = makeInput()
        val coarse = BallFlightSimulator(coarseConfiguration).simulate(input)
        val fine = BallFlightSimulator(fineConfiguration).simulate(input)

        assertThat(coarse.carryMeters).isCloseTo(fine.carryMeters, fine.carryMeters * 0.005)
        assertThat(coarse.apexMeters).isCloseTo(fine.apexMeters, 0.15)
    }

    private fun makeInput(
        speed: Double = 67.0,
        launch: Double = 13.0,
        horizontal: Double = 0.0,
        spin: Double = 2_500.0,
        spinAxis: Double = 0.0,
        carry: Double = 245.0,
    ): FlightInput =
        FlightInput(
            eventId = "11111111-2222-3333-4444-555555555555",
            ballSpeedMetersPerSecond = speed,
            launchAngleDegrees = launch,
            horizontalLaunchDegrees = horizontal,
            spinRpm = spin,
            spinAxisDegrees = spinAxis,
            targetCarryMeters = carry,
            provenance = FlightInputProvenance(),
        )

    private companion object {
        const val GRAVITY = 9.80665
        const val DEGREES_TO_RADIANS = kotlin.math.PI / 180.0
    }
}
