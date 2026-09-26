// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3
import kotlin.test.Test

/**
 * Plan F8c1: the shared scene geometry both renderers fill. Expected pixels are worked by hand for a
 * 1000 x 1000 px canvas and a 90° vertical field of view, so the focal length is exactly 500 px:
 * screen = 500 ± 500 · offset / depth.
 */
class RangeSceneGeometryTest {
    /** Eye 2 m up at z = +8, looking level down the range (−z). */
    private val levelPose = RangeCameraPose(Vec3(0.0, 2.0, 8.0), Vec3(0.0, 2.0, -100.0), verticalFovDegrees = 90.0)

    private fun projection(pose: RangeCameraPose = levelPose) = RangeProjection(pose, width = 1000f, height = 1000f)

    @Test
    fun aLevelCameraPutsTheHorizonAtTheCanvasCentre() {
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 4), RangeTheme.DAY.style, ::RecordingPathSink)

        scene.project(projection())

        // The horizon is the ground 100 km ahead: 2 m below the eye there is 500 · 2 / 100 000 px.
        assertThat(scene.horizonY.toDouble()).isCloseTo(500.01, 1e-3)
        assertThat(scene.backdropHorizon(1000f).toDouble()).isCloseTo(500.01, 1e-3)
    }

    @Test
    fun aCameraPitchedDownRaisesTheHorizonByFocalTimesTheSlope() {
        // Looking 10 m down over 100 m: the horizon sits 500 · 0.1 = 50 px above the centre.
        val pitched = RangeCameraPose(Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, -100.0), verticalFovDegrees = 90.0)
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), RangeTheme.DAY.style, ::RecordingPathSink)

        scene.project(projection(pitched))

        assertThat(scene.horizonY.toDouble()).isCloseTo(450.0, 0.1)
    }

    @Test
    fun aHorizonAboveTheCanvasLeavesNoSky() {
        val straightDown = RangeCameraPose(Vec3(0.0, 50.0, 0.0), Vec3(0.0, 0.0, -1.0), verticalFovDegrees = 90.0)
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), RangeTheme.DAY.style, ::RecordingPathSink)

        scene.project(projection(straightDown))

        assertThat(scene.horizonY).isLessThan(0f)
        assertThat(scene.backdropHorizon(1000f)).isEqualTo(0f)
    }

    @Test
    fun aPolygonInFrontOfTheCameraProjectsEveryVertex() {
        val polygon = WorldPolygon(quad(near = -20.0, far = -100.0), RangeColor.WHITE, RecordingPathSink())

        polygon.project(projection(), SceneScratch(capacity = 8))

        assertThat(polygon.visible).isTrue()
        // (±10, 0, -20) is 28 m deep, (±10, 0, -100) is 108 m deep, both 2 m below the eye.
        polygon.path.assertOutline(
            321.4286 to 535.7143,
            678.5714 to 535.7143,
            546.2963 to 509.2593,
            453.7037 to 509.2593,
        )
    }

    @Test
    fun aPolygonCrossingTheCameraIsClippedAtTheNearPlane() {
        // The near edge (z = +20) is 12 m behind the eye; Sutherland–Hodgman cuts both side edges at
        // twice the near plane (0.2 m deep, z = 7.8), where ±10 m spreads to 500 ± 25 000 px.
        val polygon = WorldPolygon(quad(near = 20.0, far = -100.0), RangeColor.WHITE, RecordingPathSink())

        polygon.project(projection(), SceneScratch(capacity = 8))

        assertThat(polygon.visible).isTrue()
        polygon.path.assertOutline(
            25_500.0 to 5_500.0,
            546.2963 to 509.2593,
            453.7037 to 509.2593,
            -24_500.0 to 5_500.0,
        )
    }

    @Test
    fun aPolygonEntirelyBehindTheCameraIsHiddenAndItsPathEmptied() {
        val polygon = WorldPolygon(quad(near = 30.0, far = 10.0), RangeColor.WHITE, RecordingPathSink())
        polygon.path.moveTo(1f, 1f)

        polygon.project(projection(), SceneScratch(capacity = 8))

        assertThat(polygon.visible).isFalse()
        assertThat(polygon.path.commands).containsExactly("rewind")
    }

    @Test
    fun spheresAndLabelsBehindTheCameraAreHidden() {
        val scratch = SceneScratch(capacity = 8)
        val behind = WorldSphere(0.0, 2.0, 20.0, radius = 1.0, color = RangeColor.WHITE)
        val ahead = WorldSphere(0.0, 2.0, -92.0, radius = 1.0, color = RangeColor.WHITE)
        val label = WorldLabel("50", 0.0, 2.0, 20.0)

        behind.project(projection(), scratch)
        ahead.project(projection(), scratch)
        label.project(projection(), scratch)

        assertThat(behind.visible).isFalse()
        assertThat(label.visible).isFalse()
        assertThat(ahead.visible).isTrue()
        assertThat(ahead.screenRadius.toDouble()).isCloseTo(5.0, 1e-4) // 500 · 1 / 100
        assertThat(ahead.screenX.toDouble()).isCloseTo(500.0, 1e-4)
        assertThat(ahead.screenY.toDouble()).isCloseTo(500.0, 1e-4)
    }

    @Test
    fun theStandardSceneUnderTheTeeCameraShowsEveryYardageTargetAndHidesNothingBehindIt() {
        val scene =
            RangeScene(RangeSceneDescription.standard(treeCount = 20), RangeTheme.DAY.style, ::RecordingPathSink)

        scene.project(RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f))

        assertThat(scene.polygons.all { it.visible }).isTrue()
        // The ground runs 59.5 m behind the camera, so its outline is clipped: its near corners
        // land far below the canvas.
        val ground = scene.polygons.first().path
        assertThat(ground.commands.first()).isEqualTo("rewind")
        assertThat(ground.points.maxOf { it.second }).isGreaterThan(2000f)
        assertThat(scene.polygons.first().color).isEqualTo(RangeTheme.DAY.style.ground)
        // Trees come back far to near.
        val depths = scene.treeOrder.map { scene.trees[it].depth }
        assertThat(depths).isEqualTo(depths.sortedDescending())
    }

    @Test
    fun theLabelFontIsItsWorldHeightClampedToTheStyleRange() {
        val label = WorldLabel("100", 0.0, 2.0, -92.0)
        label.project(projection(), SceneScratch(capacity = 8))

        // 5 px per metre at 100 m deep.
        assertThat(label.scale.toDouble()).isCloseTo(5.0, 1e-4)
        assertThat(
            label.fontPixels(heightMeters = 2.2f, minPixels = 9f, maxPixels = 16f).toDouble(),
        ).isCloseTo(11.0, 1e-4)
        assertThat(label.fontPixels(heightMeters = 1f, minPixels = 9f, maxPixels = 16f)).isEqualTo(9f)
        assertThat(label.fontPixels(heightMeters = 10f, minPixels = 9f, maxPixels = 16f)).isEqualTo(16f)
    }

    @Test
    fun theTracerIsCutAtTheNearPlaneAndTheBallHiddenBehindTheCamera() {
        // The camera stands 50 m downrange looking on, so the first half of the flight is behind it.
        val pose = RangeCameraPose(Vec3(0.0, 2.0, -50.0), Vec3(0.0, 2.0, -200.0), verticalFovDegrees = 90.0)
        val projection = projection(pose)
        val geometry = FlightGeometry.build(straightFlight(), projection, segments = 10)
        val tracer = TracerRibbon(RecordingPathSink())
        tracer.ensureCapacity(10)

        tracer.build(geometry, at = geometry.sampleAt(1f))

        assertThat(tracer.path.commands.first()).isEqualTo("rewind")
        assertThat(tracer.path.commands[1]).isEqualTo("moveTo")
        assertThat(tracer.path.commands.last()).isEqualTo("close")
        // Both edges of the ribbon, each from the near-plane crossing out to the landing.
        assertThat(tracer.path.points.size).isEqualTo(2 * 6)
        assertThat(tracer.tipX.isNaN()).isFalse()

        tracer.build(geometry, at = geometry.sampleAt(0.2f))

        assertThat(tracer.path.commands).containsExactly("rewind")
        assertThat(tracer.tipX.isNaN()).isTrue()
    }

    @Test
    fun theFramePreparesNothingBeforeItHasACanvasThenProjectsTheFlight() {
        val frame = RangeFrame(RangeTheme.DAY.style, clubPaletteSize = 8, newPath = ::RecordingPathSink)

        assertThat(frame.prepare(RangeCameraPlanner().pose, progress = 0f)).isFalse()

        frame.resize(1000f, 2000f, RangeCameraPlanner().pose)
        frame.setFlight(ActiveFlight(straightFlight(), playbackId = 1))
        assertThat(frame.prepare(RangeCameraPlanner().pose, progress = 1f)).isTrue()

        assertThat(frame.geometry).isNotNull()
        assertThat(frame.landing.size).isEqualTo(2)
        assertThat(frame.landing.all { it.visible }).isTrue()
        assertThat(frame.shadowVisible).isTrue()
        assertThat(frame.ballRadius).isGreaterThan(0f)
        assertThat(frame.flightPosition).isEqualTo(RangeFrame.QUALITY.tracerPointCount.toFloat())
    }

    @Test
    fun theOverlayGroupsTrajectoriesByPaletteColour() {
        val flights =
            listOf(
                OverlayFlight("a", "driver", colorIndex = 1, trajectory = straightFlight()),
                OverlayFlight("b", "7-iron", colorIndex = 9, trajectory = straightFlight()),
                OverlayFlight("c", "pw", colorIndex = 3, trajectory = straightFlight()),
            )
        val overlay = OverlayGeometry(flights, paletteSize = 8, newPath = ::RecordingPathSink)

        overlay.reproject(projection())

        // 9 wraps to 1, so "a" and "b" share a stroke.
        assertThat(overlay.groupColorIndices.toList()).containsExactly(1, 3)
        assertThat(overlay.landingColorIndices.toList()).containsExactly(1, 1, 3)
        assertThat(overlay.groupPaths[0].commands.count { it == "moveTo" }).isEqualTo(2)
    }

    private fun quad(
        near: Double,
        far: Double,
    ): DoubleArray = doubleArrayOf(-10.0, 0.0, near, 10.0, 0.0, near, 10.0, 0.0, far, -10.0, 0.0, far)

    /** 100 m straight down the target line, 1 m up the whole way (simulator space: +z downrange). */
    private fun straightFlight(): FlightTrajectory =
        FlightTrajectory(
            eventId = "flight",
            points =
                listOf(
                    FlightPoint(0.0, Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 50.0)),
                    FlightPoint(2.0, Vec3(0.0, 1.0, 100.0), Vec3(0.0, 0.0, 50.0)),
                ),
            provenance = FlightInputProvenance(),
        )

    private fun RecordingPathSink.assertOutline(vararg expected: Pair<Double, Double>) {
        assertThat(commands).isEqualTo(listOf("rewind", "moveTo") + List(expected.size - 1) { "lineTo" } + "close")
        assertThat(points.size).isEqualTo(expected.size)
        points.zip(expected).forEach { (actual, want) ->
            assertThat(actual.first.toDouble()).isCloseTo(want.first, 0.01)
            assertThat(actual.second.toDouble()).isCloseTo(want.second, 0.01)
        }
    }
}

/** A [PathSink] that records what the geometry wrote. */
class RecordingPathSink : PathSink {
    val commands = mutableListOf<String>()
    val points = mutableListOf<Pair<Float, Float>>()

    override fun rewind() {
        commands.clear()
        points.clear()
        commands += "rewind"
    }

    override fun moveTo(
        x: Float,
        y: Float,
    ) {
        commands += "moveTo"
        points += x to y
    }

    override fun lineTo(
        x: Float,
        y: Float,
    ) {
        commands += "lineTo"
        points += x to y
    }

    override fun close() {
        commands += "close"
    }
}
