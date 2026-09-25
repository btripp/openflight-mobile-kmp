// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test

/**
 * The follow camera through the renderers' own projection math (plans R7a, R7b): the ball stays on
 * screen for the whole flight, and the settled view frames the landing spot and the next yardage
 * marker beyond it without a nearer marker filling the foreground, on a portrait and a landscape
 * phone canvas.
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

    private val scene = RangeSceneDescription.standard(treeCount = 0)

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
    fun theSettledViewFramesTheLandingSpotAndTheNextMarkerBeyondIt() {
        for (flight in flights) {
            val landing = RangeProjection.flightToScene(flight.points.last().positionMeters)
            val heading = landingHeading(flight)
            val next =
                scene.markerScenePositions
                    .filter { along(it, landing, heading) > 0 }
                    .minBy { along(it, landing, heading) }
            val settled = rig.pose(RangeCameraMode.FOLLOW, flight, 1.0, landedElapsedSeconds = 1.25)
            for ((width, height) in canvases) {
                val projection = RangeProjection(settled, width, height)
                val label = "${flight.eventId} ${width}x$height"

                assertThat(isOnScreen(projection, landing), label).isTrue()
                // A portrait phone sees only ~30° across, and the settle turns at most ~15° off the
                // ball's heading, so there a marker far to the side of a big hook or slice may not fit.
                val fits = width > height || abs(across(next, landing, heading)) <= PORTRAIT_MARKER_LATERAL_METERS
                if (fits) assertThat(isOnScreen(projection, next), "$label marker $next").isTrue()
            }
            // Looking down at it from above.
            assertThat(settled.position.y).isGreaterThan(landing.y + MIN_SETTLED_HEIGHT_METERS)
            assertThat(settled.target.y).isLessThan(settled.position.y)
        }
    }

    @Test
    fun noYardageMarkerFillsTheForegroundOfTheSettledView() {
        for (flight in flights) {
            val landing = RangeProjection.flightToScene(flight.points.last().positionMeters)
            val settled = rig.pose(RangeCameraMode.FOLLOW, flight, 1.0, landedElapsedSeconds = 1.25)
            // Markers short of the landing spot, not the one it landed on, on each canvas.
            val shortMarkers =
                scene.markerScenePositions
                    .zip(scene.markers.map { it.radiusMeters })
                    .filter { (marker, radius) -> along(marker, landing, landingHeading(flight)) <= -radius }
            for ((canvas, markerAndRadius) in canvases.flatMap { canvas -> shortMarkers.map { canvas to it } }) {
                val (width, height) = canvas
                val (marker, radius) = markerAndRadius
                assertThat(
                    screenCoverage(settled, width, height, marker, radius),
                    "${flight.eventId} ${width}x$height marker $marker",
                ).isLessThan(MAX_FOREGROUND_MARKER_COVERAGE)
            }
        }
    }

    @Test
    fun aBallLandingJustPastAMarkerDoesNotPutItsDiscInTheForeground() {
        // The live R7b case: a 211-yd carry lands ~10 m past the 200-yd marker's centre.
        for ((index, marker) in scene.markerScenePositions.withIndex()) {
            val radius = scene.markers[index].radiusMeters
            for ((past, lateral) in PAST_MARKER_METERS.flatMap { past -> LANDING_LATERALS.map { past to it } }) {
                val landing = Vec3(lateral, 0.0, marker.z - past)
                val settled = rig.pose(RangeCameraMode.FOLLOW, straightFlightTo(landing), 1.0, 1.25)
                val worst = canvases.maxOf { (width, height) -> screenCoverage(settled, width, height, marker, radius) }

                assertThat(worst, "marker ${marker.z} past $past lateral $lateral")
                    .isLessThan(MAX_FOREGROUND_MARKER_COVERAGE)
            }
        }
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

    /** The share of the canvas whose ray hits the ground inside the disc at [center] (a coarse ray cast). */
    private fun screenCoverage(
        pose: RangeCameraPose,
        width: Float,
        height: Float,
        center: Vec3,
        radius: Double,
    ): Double {
        val forward = (pose.target - pose.position).normalized()
        val right = Vec3(-forward.z, 0.0, forward.x).normalized()
        val up = right cross forward
        val focal = (height / 2) / tan(pose.verticalFovDegrees * PI / 360.0)
        var hits = 0
        for (column in 0 until COVERAGE_COLUMNS) {
            for (row in 0 until COVERAGE_ROWS) {
                val dx = ((column + 0.5) / COVERAGE_COLUMNS * width - width / 2) / focal
                val dy = -((row + 0.5) / COVERAGE_ROWS * height - height / 2) / focal
                val ray = forward + right * dx + up * dy
                if (ray.y >= 0) continue
                val t = -pose.position.y / ray.y
                val hitX = pose.position.x + ray.x * t - center.x
                val hitZ = pose.position.z + ray.z * t - center.z
                if (hitX * hitX + hitZ * hitZ <= radius * radius) hits++
            }
        }
        return hits.toDouble() / (COVERAGE_COLUMNS * COVERAGE_ROWS)
    }

    private fun landingHeading(flight: FlightTrajectory): Vec3 {
        val velocity = RangeProjection.flightToScene(flight.points.last().velocityMetersPerSecond)
        return Vec3(velocity.x, 0.0, velocity.z).normalized()
    }

    /** How far [point] is to the side of the line through [landing] along [heading]. */
    private fun across(
        point: Vec3,
        landing: Vec3,
        heading: Vec3,
    ): Double = (point.x - landing.x) * heading.z - (point.z - landing.z) * heading.x

    /** How far [point] is past [landing] along [heading] (negative: short of it). */
    private fun along(
        point: Vec3,
        landing: Vec3,
        heading: Vec3,
    ): Double = (point.x - landing.x) * heading.x + (point.z - landing.z) * heading.z

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
        const val MIN_SETTLED_HEIGHT_METERS = 7.5
        const val PORTRAIT_MARKER_LATERAL_METERS = 12.0
        val PAST_MARKER_METERS = listOf(4.0, 7.0, 10.0, 13.0, 18.0, 25.0)
        val LANDING_LATERALS = listOf(-4.0, 0.0, 4.0)
        const val COVERAGE_COLUMNS = 36
        const val COVERAGE_ROWS = 72

        /** A marker short of the landing spot may show, but never as a disc filling the frame. */
        const val MAX_FOREGROUND_MARKER_COVERAGE = 0.12
    }
}

private fun Vec3.normalized(): Vec3 = this / length()
