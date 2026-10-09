// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotEqualTo
import dev.openflight.companion.core.model.Conditions
import kotlin.test.Test

class FlightPathProjectorTest {
    private val fade: ShotFlightPath =
        ShotFlightPath.from(
            checkNotNull(
                ShotFlightPlanner().plan(
                    FlightMeasurements(
                        id = "fade",
                        club = "7-iron",
                        ballSpeedMph = 120.0,
                        carryYards = 165.0,
                        launchAngleVertical = 16.0,
                        launchAngleHorizontal = 0.0,
                        spinRpm = 7000.0,
                        spinAxisDeg = 12.0,
                    ),
                    Conditions.ISA,
                    null,
                ),
            ),
        )!!

    private val width = 360f
    private val height = 220f

    private fun project(
        view: FlightView,
        orbit: Float = 0f,
    ) = FlightPathProjector.project(fade, view, width, height, orbitDegrees = orbit)

    @Test
    fun everyViewFitsTheFlightInsideTheBox() {
        FlightView.entries.forEach { view ->
            val projected = project(view)
            val points = projected.path + projected.roll + projected.targetLine
            points.forEach {
                assertThat(it.x).isBetween(-0.01f, width + 0.01f)
                assertThat(it.y).isBetween(-0.01f, height + 0.01f)
            }
        }
    }

    @Test
    fun fromAboveTheTeeIsAtTheBottomAndAFadeLandsRightOfIt() {
        val projected = project(FlightView.TOP)
        val tee = projected.path.first()
        val landing = projected.path.last()

        assertThat(landing.y).isLessThan(tee.y)
        assertThat(landing.x).isGreaterThan(tee.x)
        assertThat(tee.x).isCloseTo(projected.targetLine.first().x, 0.01f)
    }

    @Test
    fun fromTheSideTheBallRisesAndComesDownDownrange() {
        val projected = project(FlightView.SIDE)
        val tee = projected.path.first()
        val landing = projected.path.last()
        val highest = projected.path.minOf { it.y }

        assertThat(landing.x).isGreaterThan(tee.x)
        assertThat(landing.y).isCloseTo(tee.y, 0.5f)
        assertThat(highest).isLessThan(tee.y - height / 4)
    }

    @Test
    fun fromBehindAFadeLandsRightOfTheTargetLine() {
        val projected = project(FlightView.BEHIND)
        val landing = projected.path.last()
        val (start, end) = projected.targetLine
        val along = (landing.y - start.y) / (end.y - start.y)
        val lineX = start.x + (end.x - start.x) * along

        assertThat(landing.x).isGreaterThan(lineX)
    }

    @Test
    fun orbitingMovesTheCameraButIsClamped() {
        val home = project(FlightView.BEHIND).path.last()

        assertThat(project(FlightView.BEHIND, orbit = 30f).path.last()).isNotEqualTo(home)
        assertThat(project(FlightView.BEHIND, orbit = 500f))
            .isEqualTo(project(FlightView.BEHIND, orbit = FlightPathProjector.MAX_ORBIT_DEGREES))
    }

    @Test
    fun fromBehindTheFlightFillsTheBox() {
        val path = project(FlightView.BEHIND).path
        val tallest = path.maxOf { it.y } - path.minOf { it.y }

        // The bug this guards against: framing the guides squeezed the flight into a few pixels.
        assertThat(tallest).isGreaterThan(height / 3)
    }

    @Test
    fun markersNeverCrowdEachOther() {
        FlightView.entries.forEach { view ->
            project(view).markers.zipWithNext { a, b ->
                val gap = kotlin.math.hypot(b.position.x - a.position.x, b.position.y - a.position.y)
                assertThat(gap).isGreaterThan(17f)
            }
        }
    }

    @Test
    fun aPatternFramesEveryFlightAndKeepsDrawsLeftOfFades() {
        val draw =
            ShotFlightPath.from(
                checkNotNull(
                    ShotFlightPlanner().plan(
                        FlightMeasurements("draw", "7-iron", 120.0, 165.0, 16.0, 0.0, 7000.0, -12.0),
                        Conditions.ISA,
                        null,
                    ),
                ),
            )!!
        val pattern = FlightPathProjector.projectPattern(listOf(fade, draw), width, height)!!

        pattern.flights.flatten().forEach {
            assertThat(it.x).isBetween(-0.01f, width + 0.01f)
            assertThat(it.y).isBetween(-0.01f, height + 0.01f)
        }
        assertThat(pattern.flights[1].last().x).isLessThan(pattern.targetLine.first().x)
        assertThat(pattern.flights[0].last().x).isGreaterThan(pattern.targetLine.first().x)
        assertThat(pattern.flights[0].first()).isEqualTo(pattern.flights[1].first())
    }

    @Test
    fun anEmptyPatternIsNull() {
        assertThat(FlightPathProjector.projectPattern(emptyList(), width, height)).isEqualTo(null)
    }

    @Test
    fun theBallPlaysFromTheTeeToTheLanding() {
        val projected = project(FlightView.SIDE)

        assertThat(projected.ballAt(0f)).isEqualTo(projected.path.first())
        assertThat(projected.ballAt(1f)).isEqualTo(projected.path.last())
        assertThat(projected.ballAt(2f)).isEqualTo(projected.path.last())
    }

    @Test
    fun theTargetLineCarriesDistanceMarkers() {
        val markers = project(FlightView.TOP).markers

        assertThat(markers.map { it.yards }.take(3)).isEqualTo(listOf(50, 100, 150))
    }
}
