// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
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
    fun crownLobesAndLabelsBehindTheCameraAreHidden() {
        val scratch = SceneScratch(capacity = 8)
        val behind = WorldCrown(doubleArrayOf(0.0, 2.0, 20.0, 1.0), RangeColor.WHITE, RecordingPathSink())
        val ahead = WorldCrown(doubleArrayOf(0.0, 2.0, -92.0, 1.0), RangeColor.WHITE, RecordingPathSink())
        val label = WorldLabel("50", 0.0, 2.0, 20.0)

        behind.project(projection(), scratch)
        ahead.project(projection(), scratch)
        label.project(projection(), scratch)

        assertThat(behind.visible).isFalse()
        assertThat(behind.path.commands).containsExactly("rewind")
        assertThat(label.visible).isFalse()
        assertThat(ahead.visible).isTrue()
        // A 24-sided outline of radius 500 · 1 / 100 = 5 px around the canvas centre.
        assertThat(ahead.path.points.size).isEqualTo(24)
        assertThat(ahead.path.commands.last()).isEqualTo("close")
        for ((x, y) in ahead.path.points) {
            assertThat(kotlin.math.hypot(x - 500.0, y - 500.0)).isCloseTo(5.0, 1e-3)
        }
    }

    @Test
    fun aCrownWritesEveryLobeIntoOnePath() {
        val crown =
            WorldCrown(
                doubleArrayOf(-1.0, 2.0, -92.0, 1.0, 1.0, 2.0, -92.0, 1.0, 0.0, 2.0, 30.0, 1.0),
                RangeColor.WHITE,
                RecordingPathSink(),
            )

        crown.project(projection(), SceneScratch(capacity = 8))

        // Two lobes in front, one behind the camera: two closed subpaths.
        assertThat(crown.path.commands.count { it == "moveTo" }).isEqualTo(2)
        assertThat(crown.path.commands.count { it == "close" }).isEqualTo(2)
    }

    @Test
    fun aMultiRingPolygonClipsEachRingAndSkipsTheOnesBehind() {
        val rings = quad(near = -20.0, far = -100.0) + quad(near = 30.0, far = 10.0) + quad(near = 20.0, far = -100.0)
        val polygon = WorldPolygon(rings, RangeColor.WHITE, RecordingPathSink(), intArrayOf(4, 4, 4))

        polygon.project(projection(), SceneScratch(capacity = 8))

        assertThat(polygon.visible).isTrue()
        assertThat(polygon.path.commands.count { it == "moveTo" }).isEqualTo(2)
        assertThat(polygon.path.commands.count { it == "close" }).isEqualTo(2)
        assertThat(polygon.path.points.size).isEqualTo(8)
    }

    @Test
    fun theSunSitsAtItsElevationAboveTheHorizonAndTurnsWithTheCamera() {
        // DAY's sun: 32° left, 17° up. Face it with a level camera: it's on the vertical centre
        // line, 500 · tan 17° above the horizon (at the canvas centre).
        val sun = RangeTheme.DAY.style.sun
        val azimuth = radians(sun.azimuthDegrees)
        val facing =
            RangeCameraPose(
                Vec3(0.0, 2.0, 8.0),
                Vec3(100 * kotlin.math.sin(azimuth), 2.0, 8.0 - 100 * kotlin.math.cos(azimuth)),
                verticalFovDegrees = 90.0,
            )
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), RangeTheme.DAY.style, ::RecordingPathSink)

        scene.project(projection(facing))

        assertThat(scene.sky.sunVisible).isTrue()
        assertThat(scene.sky.sunX.toDouble()).isCloseTo(500.0, 0.05)
        assertThat(scene.sky.sunY.toDouble()).isCloseTo(500.0 - 500 * kotlin.math.tan(radians(17.0)), 0.1)
        assertThat(scene.sky.sunDiscRadius.toDouble()).isCloseTo(500 * kotlin.math.tan(radians(1.1)), 1e-3)
        assertThat(scene.sky.sunGlowRadius).isGreaterThan(scene.sky.sunDiscRadius)

        // Travelling 300 m down the range doesn't move it: it's at infinity.
        val travelled =
            facing.copy(
                position = facing.position.copy(z = -292.0),
                target =
                    facing.target.copy(
                        z =
                            facing.target.z - 300,
                    ),
            )
        scene.project(projection(travelled))
        assertThat(scene.sky.sunX.toDouble()).isCloseTo(500.0, 0.05)

        // Looking the other way, it's behind the camera.
        scene.project(
            projection(RangeCameraPose(Vec3(0.0, 2.0, 8.0), Vec3(0.0, 2.0, 108.0), verticalFovDegrees = 90.0)),
        )
        assertThat(scene.sky.sunVisible).isFalse()
    }

    @Test
    fun theRidgesStandOnTheHorizonAcrossTheViewAndRiseWithinTheirHeights() {
        val style = RangeTheme.DAY.style
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), style, ::RecordingPathSink)

        scene.project(projection())

        assertThat(scene.sky.ridges.size).isEqualTo(style.ridges.size)
        for ((index, ridge) in scene.sky.ridges.withIndex()) {
            assertThat(ridge.visible).isTrue()
            assertThat(ridge.color).isEqualTo(style.ridges[index].color)
            val points = ridge.path.points
            // It spans the canvas side to side, closing a couple of pixels under the horizon.
            assertThat(points.minOf { it.first }).isLessThan(0f)
            assertThat(points.maxOf { it.first }).isGreaterThan(1000f)
            assertThat(points.maxOf { it.second }.toDouble()).isCloseTo(scene.horizonY + 2.0, 1e-3)
            val top = points.dropLast(2)
            val lowest = 500 * kotlin.math.tan(radians(style.ridges[index].baseDegrees))
            val highest =
                500 * kotlin.math.tan(radians(style.ridges[index].baseDegrees + style.ridges[index].amplitudeDegrees))
            // Heights above the horizon, allowing for the tangent's growth off the view axis.
            for ((_, y) in top) {
                assertThat(scene.horizonY - y).isGreaterThan((lowest * 0.99).toFloat())
            }
            assertThat(top.maxOf { scene.horizonY - it.second }.toDouble()).isLessThan(highest * 6)
        }
    }

    @Test
    fun theSameRidgeSeedDrawsTheSameSkyline() {
        val ridge =
            RangeTheme.DAY.style.ridges
                .first()
        val again = ridge.copy()
        val other = ridge.copy(seed = ridge.seed + 1)

        val heights = (0 until RangeSky.RIDGE_SAMPLES).map { RangeSky.ridgeDegrees(ridge, it) }
        assertThat((0 until RangeSky.RIDGE_SAMPLES).map { RangeSky.ridgeDegrees(again, it) }).isEqualTo(heights)
        assertThat((0 until RangeSky.RIDGE_SAMPLES).map { RangeSky.ridgeDegrees(other, it) }).isNotEqualTo(heights)
        assertThat(heights.min()).isGreaterThanOrEqualTo(ridge.baseDegrees)
        assertThat(heights.max()).isLessThanOrEqualTo(ridge.baseDegrees + ridge.amplitudeDegrees)
    }

    @Test
    fun seenFromTheTeeTheGroundAndFairwayBandsHazeMoreWithDistance() {
        val style = RangeTheme.DAY.style
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), style, ::RecordingPathSink)

        scene.project(RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f))

        // The rough's nine bands come first, then the fairway's six, near to far.
        val rough = scene.polygons.take(9)
        val fairway = scene.polygons.drop(9).take(6)
        assertThat(rough.map { it.color }.toSet()).isEqualTo(setOf(style.ground))
        assertThat(fairway.map { it.color }.toSet()).isEqualTo(setOf(style.fairway))
        assertThat(fairway.first().argb).isEqualTo(style.fairway.toArgb())
        for (bands in listOf(rough, fairway)) {
            val towardHaze = bands.map { kotlin.math.abs(blue(it.argb) - blue(style.haze.color.toArgb())) }
            assertThat(towardHaze).isEqualTo(towardHaze.sortedDescending())
            assertThat(towardHaze.first()).isGreaterThan(towardHaze.last())
        }
    }

    @Test
    fun theHazeIsMeasuredFromTheCameraSoTheFollowCameraSeesClearGrassBelowIt() {
        val style = RangeTheme.DAY.style
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 4), style, ::RecordingPathSink)
        // The fairway band 174–246 m down the range, and the tree 22 m down it.
        val band = scene.polygons[9 + 3]
        val nearTree = scene.trees.last()

        scene.project(RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f))
        assertThat(band.argb).isNotEqualTo(style.fairway.toArgb())
        val hazeBlue = blue(style.haze.color.toArgb())
        val treeFromTheTee = kotlin.math.abs(blue(nearTree.crowns.first().argb) - hazeBlue)

        // A camera hovering over that band, 210 m down the range, looking on.
        val overTheBand = RangeCameraPose(Vec3(0.0, 30.0, -210.0), Vec3(0.0, 0.0, -300.0))
        scene.project(RangeProjection(overTheBand, width = 1000f, height = 2000f))

        assertThat(band.argb).isEqualTo(style.fairway.toArgb())
        // The tee's tree is now far behind it, and hazier.
        assertThat(kotlin.math.abs(blue(nearTree.crowns.first().argb) - hazeBlue)).isLessThan(treeFromTheTee)
    }

    private fun blue(argb: Int): Int = argb and 0xFF

    @Test
    fun theTracerGlowIsTheCoreRibbonWider() {
        val geometry = FlightGeometry.build(straightFlight(), projection(), segments = 10)
        val tracer = TracerRibbon(RecordingPathSink(), RecordingPathSink(), glowWidthFactor = 3f)
        tracer.ensureCapacity(10)

        tracer.build(geometry, at = geometry.sampleAt(1f))

        val core = tracer.path.points
        val glow = tracer.glow!!.points
        assertThat(glow.size).isEqualTo(core.size)
        // The first point pair spans the ribbon's width at the tee: three times as wide in the glow.
        val coreWidth =
            kotlin.math.hypot(
                (core.first().first - core.last().first).toDouble(),
                (core.first().second - core.last().second).toDouble(),
            )
        val glowWidth =
            kotlin.math.hypot(
                (glow.first().first - glow.last().first).toDouble(),
                (glow.first().second - glow.last().second).toDouble(),
            )
        assertThat(glowWidth).isCloseTo(coreWidth * 3, 1e-3)
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
        // Every tree has a trunk and three crown tones in view.
        for (tree in scene.trees) {
            assertThat(tree.crowns.size).isEqualTo(3)
            assertThat(tree.crowns.all { it.visible }).isTrue()
        }
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

    private fun radians(degrees: Double): Double = degrees * kotlin.math.PI / 180

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
