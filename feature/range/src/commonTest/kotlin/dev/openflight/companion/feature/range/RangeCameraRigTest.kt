// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.test.Test

/**
 * The follow camera through the renderers' own projection math (plan R7a): the ball stays on
 * screen for the whole flight, and the settled view frames the landing spot and the nearest
 * yardage marker, on a portrait and a landscape phone canvas.
 */
class RangeCameraRigTest {
    private val rig = RangeCameraRig()

    private val flights: List<FlightTrajectory> =
        listOf(
            makeDrivingRangeShot(),
            makeDrivingRangeShot(horizontalLaunch = 4.0, spinAxis = 25.0),
            makeDrivingRangeShot(horizontalLaunch = -5.0, spinAxis = -30.0, carryYards = 230.0),
            makeDrivingRangeShot(
                club = "pw",
                ballSpeedMph = 95.0,
                carryYards = 120.0,
                launchAngle = 30.0,
                spinRpm = 8_500.0,
                spinAxis = 0.0,
            ),
            makeDrivingRangeShot(
                club = "7-iron",
                ballSpeedMph = 110.0,
                carryYards = 140.0,
                launchAngle = 6.0,
                spinRpm = 3_000.0,
                spinAxis = -8.0,
            ),
        ).map { BallFlightSimulator().simulate(FlightInputResolver().resolve(it)) }

    private val canvases = listOf(PORTRAIT_WIDTH to PORTRAIT_HEIGHT, PORTRAIT_HEIGHT to PORTRAIT_WIDTH)

    @Test
    fun theFixedModeAlwaysUsesTheTeeCamera() {
        val flight = flights.first()

        for (progress in listOf(0.0, 0.5, 1.0)) {
            assertThat(rig.pose(RangeCameraMode.FIXED, flight, progress, 0.5)).isEqualTo(RangeCameraPlanner().pose)
        }
    }

    @Test
    fun withoutAFlightTheFollowModeUsesTheTeeCamera() {
        assertThat(rig.pose(RangeCameraMode.FOLLOW, null, 0.3, 0.0)).isEqualTo(rig.fixedPose)
    }

    @Test
    fun theFollowModeStartsOnTheTeeCamera() {
        assertThat(rig.pose(RangeCameraMode.FOLLOW, flights.first(), 0.0, 0.0)).isEqualTo(rig.fixedPose)
    }

    @Test
    fun theBallStaysOnScreenForTheWholeFollowedFlight() {
        for (flight in flights) {
            for ((width, height) in canvases) {
                for (step in 0..PROGRESS_STEPS) {
                    val progress = step.toDouble() / PROGRESS_STEPS
                    val ball = ballAt(flight, progress)
                    val projection =
                        RangeProjection(rig.pose(RangeCameraMode.FOLLOW, flight, progress, 0.0), width, height)
                    val label = "${flight.eventId} ${width}x$height at $progress"

                    assertThat(isOnScreen(projection, ball), label).isTrue()
                }
            }
        }
    }

    @Test
    fun theSettledViewFramesTheLandingSpotAndTheNearestMarker() {
        val markers = RangeSceneDescription.standard(treeCount = 0).markerScenePositions
        for (flight in flights) {
            val landing = RangeProjection.flightToScene(flight.points.last().positionMeters)
            val nearest =
                markers.minBy {
                    (it.x - landing.x) * (it.x - landing.x) +
                        (it.z - landing.z) * (it.z - landing.z)
                }
            val settled = rig.pose(RangeCameraMode.FOLLOW, flight, 1.0, landedElapsedSeconds = 1.25)
            for ((width, height) in canvases) {
                val projection = RangeProjection(settled, width, height)
                val label = "${flight.eventId} ${width}x$height"

                assertThat(isOnScreen(projection, landing), label).isTrue()
                assertThat(isOnScreen(projection, nearest), "$label marker $nearest").isTrue()
            }
            // Looking down at it from above.
            assertThat(settled.position.y).isGreaterThan(landing.y + 10)
            assertThat(settled.target.y).isLessThan(settled.position.y)
        }
    }

    private fun ballAt(
        flight: FlightTrajectory,
        progress: Double,
    ): Vec3 = RangeProjection.flightToScene(flight.point(progress * flight.flightTime)!!.positionMeters)

    /** In front of the camera and inside the canvas with a small margin. */
    private fun isOnScreen(
        projection: RangeProjection,
        point: Vec3,
    ): Boolean {
        val screen = projection.project(point)
        val marginX = projection.width * MARGIN_FRACTION
        val marginY = projection.height * MARGIN_FRACTION
        return screen.isSpecified &&
            screen.x in marginX..(projection.width - marginX) &&
            screen.y in marginY..(projection.height - marginY)
    }

    private companion object {
        const val PORTRAIT_WIDTH = 1080f
        const val PORTRAIT_HEIGHT = 2400f
        const val PROGRESS_STEPS = 240
        const val MARGIN_FRACTION = 0.03f
    }
}
