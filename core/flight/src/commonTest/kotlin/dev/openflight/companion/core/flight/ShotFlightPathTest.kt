// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isIn
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import dev.openflight.companion.core.model.Conditions
import kotlin.test.Test

class ShotFlightPathTest {
    private val planner = ShotFlightPlanner()

    private fun sevenIron(
        horizontal: Double? = 0.0,
        spinAxis: Double? = 0.0,
    ) = FlightMeasurements(
        id = "shot",
        club = "7-iron",
        ballSpeedMph = 120.0,
        carryYards = 165.0,
        launchAngleVertical = 16.0,
        launchAngleHorizontal = horizontal,
        spinRpm = 7000.0,
        spinAxisDeg = spinAxis,
    )

    private fun path(measurements: FlightMeasurements): ShotFlightPath =
        ShotFlightPath.from(checkNotNull(planner.plan(measurements, Conditions.ISA, null)))!!

    @Test
    fun theFlightRunsFromTheTeeToTheDisplayedCarry() {
        val flight = path(sevenIron())

        assertThat(flight.points.size).isEqualTo(ShotFlightPath.DEFAULT_SAMPLES)
        assertThat(flight.points.first()).isEqualTo(Vec3.ZERO)
        assertThat(flight.carryYards).isCloseTo(165.0, 0.5)
        assertThat(flight.apexYards).isGreaterThan(10.0)
        assertThat(flight.descentDegrees).isBetween(20.0, 70.0)
    }

    @Test
    fun theRollCarriesTheBallOnPastTheLanding() {
        val flight = path(sevenIron())

        assertThat(flight.rest.z).isGreaterThan(flight.landing.z)
        assertThat(flight.rest.y).isEqualTo(0.0)
    }

    @Test
    fun aRightTiltedSpinAxisCurvesRightAndALeftOneCurvesLeft() {
        assertThat(path(sevenIron(spinAxis = 8.0)).landing.x).isGreaterThan(1.0)
        assertThat(path(sevenIron(spinAxis = -8.0)).landing.x).isLessThan(-1.0)
    }

    @Test
    fun theShapeFollowsTheDrawnCurve() {
        assertThat(path(sevenIron(spinAxis = 0.0)).shape.curve).isEqualTo(Curve.STRAIGHT)
        assertThat(path(sevenIron(spinAxis = 8.0)).shape.curve).isIn(Curve.FADE, Curve.SLICE)
        assertThat(path(sevenIron(spinAxis = -8.0)).shape.curve).isIn(Curve.DRAW, Curve.HOOK)
        assertThat(path(sevenIron(spinAxis = 40.0)).shape.curve).isEqualTo(Curve.SLICE)
        assertThat(path(sevenIron(spinAxis = -40.0)).shape.curve).isEqualTo(Curve.HOOK)
    }

    @Test
    fun theStartLineFollowsTheHorizontalLaunch() {
        assertThat(path(sevenIron(horizontal = 4.0)).shape.start).isEqualTo(StartLine.PUSH)
        assertThat(path(sevenIron(horizontal = -4.0)).shape.start).isEqualTo(StartLine.PULL)
        assertThat(path(sevenIron(horizontal = 1.0)).shape.start).isEqualTo(StartLine.STRAIGHT)
    }

    @Test
    fun aPushedBallThatHoldsItsLineIsAPushNotAFade() {
        val shape = path(sevenIron(horizontal = 4.0, spinAxis = 0.0)).shape

        assertThat(shape.curve).isEqualTo(Curve.STRAIGHT)
        assertThat(shape.label).isEqualTo("Push")
    }

    @Test
    fun anUnmeasuredSpinAxisOrStartLineIsNotNamed() {
        assertThat(path(sevenIron(spinAxis = null)).shape.curve).isNull()
        assertThat(path(sevenIron(horizontal = null)).shape.start).isNull()
        assertThat(path(sevenIron(horizontal = null, spinAxis = null)).shape.label).isNull()
    }
}
