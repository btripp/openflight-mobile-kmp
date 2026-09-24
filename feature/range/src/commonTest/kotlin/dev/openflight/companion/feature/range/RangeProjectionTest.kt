// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.Vec3
import kotlin.test.Test

/**
 * The expected pixels were computed independently (a look-at pinhole camera in Python) for the
 * planner's pose, a 58° vertical field of view and a 1000 x 2000 px canvas.
 */
class RangeProjectionTest {
    private val projection = RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f)

    @Test
    fun focalLengthComesFromTheVerticalFieldOfView() {
        assertThat(projection.focalLengthPixels).isCloseTo(1804.0478, 0.001)
    }

    @Test
    fun theCameraTargetProjectsToTheCanvasCentre() {
        assertPoint(projection.project(Vec3(0.0, 9.0, -145.0)), x = 500.0, y = 1000.0)
    }

    @Test
    fun theBallOnTheTeeProjectsNearTheBottomCentre() {
        val scene = RangeProjection.flightToScene(Vec3.ZERO)

        assertThat(scene).isEqualTo(Vec3(0.0, 0.18, -0.0))
        assertPoint(projection.project(scene), x = 500.0, y = 1804.0043)
    }

    @Test
    fun aFlightPointDownrangeRightAndUpProjectsToItsKnownPixel() {
        // Simulator space: 10 m right, 20 m up, 100 m downrange (+z).
        val scene = RangeProjection.flightToScene(Vec3(10.0, 20.0, 100.0))

        assertPoint(projection.project(scene), x = 666.2081, y = 786.9465)
    }

    @Test
    fun aPointBehindTheCameraIsNotDrawable() {
        val behind = projection.project(Vec3(0.0, 3.4, 9.0))

        assertThat(behind).isEqualTo(ScreenPoint.Unspecified)
        assertThat(behind.isSpecified).isFalse()
    }

    @Test
    fun reducedMotionFliesInPointNineSeconds() {
        val trajectory = straightDrive(carryMeters = 240.0, flightTime = 7.0)

        assertThat(playbackSeconds(trajectory, reduceMotion = true)).isEqualTo(0.9)
        assertThat(playbackSeconds(trajectory, reduceMotion = false)).isCloseTo(7.0 * 0.68, 1e-9)
    }

    @Test
    fun aStraightDriveGoesUpTheScreenAlongTheTargetLine() {
        val geometry =
            FlightGeometry.build(
                straightDrive(carryMeters = 240.0, flightTime = 6.0),
                projection,
                segments = 72,
            )

        assertThat(geometry.sampleCount).isEqualTo(73)
        val tee = geometry.pointAt(0f)
        val landing = geometry.pointAt(72f)
        assertThat(landing.y).isLessThan(tee.y)
        assertThat(landing.y).isGreaterThan(0f)
        assertThat(landing.x.toDouble()).isCloseTo(500.0, 0.01)
        assertThat(geometry.landing).isEqualTo(Vec3(0.0, 0.0, -240.0))
        // The tracer thins with distance even though its width in metres grows.
        assertThat(geometry.tracerWidths[72]).isLessThan(geometry.tracerWidths[0])
    }

    private fun assertPoint(
        point: ScreenPoint,
        x: Double,
        y: Double,
    ) {
        assertThat(point.x.toDouble()).isCloseTo(x, 0.01)
        assertThat(point.y.toDouble()).isCloseTo(y, 0.01)
    }

    /** A symmetric arc straight down the target line, carrying [carryMeters]. */
    private fun straightDrive(
        carryMeters: Double,
        flightTime: Double,
    ): FlightTrajectory {
        val steps = 60
        val points =
            (0..steps).map { step ->
                val fraction = step.toDouble() / steps
                FlightPoint(
                    time = flightTime * fraction,
                    positionMeters = Vec3(0.0, 120.0 * fraction * (1 - fraction), carryMeters * fraction),
                    velocityMetersPerSecond = Vec3.ZERO,
                )
            }
        return FlightTrajectory(eventId = "straight", points = points, provenance = FlightInputProvenance())
    }
}
