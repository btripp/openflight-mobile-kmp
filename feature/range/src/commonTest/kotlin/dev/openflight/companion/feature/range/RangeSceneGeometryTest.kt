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
    fun theHazeIsMeasuredFromTheCameraSoTheFollowCameraSeesClearGrassBelowIt() {
        val style = RangeTheme.DAY.style
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 4), style, ::RecordingPathSink)
        // The 200 yd target (the fourth, 183 m down the range), and the tree 22 m down it.
        val target = scene.polygons[2 * 3]
        val nearTree = scene.trees.last()

        scene.project(RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f))
        val hazeBlue = blue(style.haze.color.toArgb())
        val targetFromTheTee = kotlin.math.abs(blue(target.argb) - hazeBlue)
        val treeFromTheTee = kotlin.math.abs(blue(nearTree.crowns.first().argb) - hazeBlue)
        assertThat(target.argb).isNotEqualTo(target.color.toArgb())

        // A camera hovering just short of that target, looking on down the range.
        val overTheTarget = RangeCameraPose(Vec3(0.0, 30.0, -160.0), Vec3(0.0, 0.0, -300.0))
        scene.project(RangeProjection(overTheTarget, width = 1000f, height = 2000f))

        assertThat(kotlin.math.abs(blue(target.argb) - hazeBlue)).isGreaterThan(targetFromTheTee)
        // The tee's tree is now far behind it: behind the camera, nothing is hazed.
        assertThat(nearTree.crowns.first().argb).isEqualTo(
            nearTree.crowns
                .first()
                .color
                .toArgb(),
        )
        assertThat(treeFromTheTee).isLessThan(
            kotlin.math.abs(
                blue(
                    nearTree.crowns
                        .first()
                        .color
                        .toArgb(),
                ) - hazeBlue,
            ),
        )
    }

    // Plan F8a2p: the feathered ground.

    @Test
    fun theHazeOverlayRunsFromFullHazeAtTheHorizonToClearInEvenSteps() {
        for (theme in RangeTheme.entries) {
            val haze = theme.style.haze
            val stops = haze.overlayStops
            val name = theme.name

            assertThat(stops.first().offset, name).isEqualTo(0f)
            assertThat(
                stops
                    .first()
                    .color.alpha
                    .toDouble(),
                name,
            ).isCloseTo(haze.maxAmount.toDouble(), 1e-6)
            assertThat(stops.last().offset, name).isEqualTo(1f)
            assertThat(
                stops
                    .last()
                    .color.alpha
                    .toDouble(),
                name,
            ).isCloseTo(0.0, 1e-6)
            assertThat(stops.map { it.color.copy(alpha = 1f) }.toSet(), name).isEqualTo(setOf(haze.color))
            for ((near, far) in stops.zipWithNext()) {
                // Ordered, and no big jump between neighbouring stops.
                assertThat(far.offset, name).isGreaterThan(near.offset)
                assertThat(near.color.alpha - far.color.alpha, name).isGreaterThan(0f)
                assertThat((near.color.alpha - far.color.alpha).toDouble(), name)
                    .isLessThanOrEqualTo(haze.maxAmount / 16.0 + 1e-6)
            }
        }
    }

    @Test
    fun theHazeOverlayMatchesTheHazeAtEveryDistanceWithinAFewPercent() {
        for (theme in RangeTheme.entries) {
            val haze = theme.style.haze
            val stops = haze.overlayStops
            for (step in 1..1_000) {
                val u = step / 1_000f
                val exact = haze.amount(haze.referenceMeters / u)
                val upper = stops.indexOfFirst { it.offset >= u }
                val lower = stops[upper - 1]
                val t = (u - lower.offset) / (stops[upper].offset - lower.offset)
                val drawn = lower.color.alpha + (stops[upper].color.alpha - lower.color.alpha) * t
                assertThat((drawn - exact).toDouble(), "${theme.name} at u $u").isCloseTo(0.0, 0.03)
            }
        }
    }

    @Test
    fun theHazeOverlayIsLinearInScreenYSoOneGradientHazesTheWholeGround() {
        val style = RangeTheme.DAY.style
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), style, ::RecordingPathSink)
        val poses =
            listOf(
                RangeCameraPlanner().pose,
                RangeCameraPose(Vec3(6.0, 34.0, -120.0), Vec3(-4.0, 3.0, -230.0), verticalFovDegrees = 62.0),
            )
        for (pose in poses) {
            val projection = RangeProjection(pose, width = 1000f, height = 2000f)
            scene.project(projection)
            assertThat(scene.hazeVisible).isTrue()
            assertThat(scene.hazeTopY).isEqualTo(scene.horizonY)
            assertThat(scene.hazeBottomY).isGreaterThan(scene.hazeTopY)
            for ((x, downrange) in listOf(0.0 to 60.0, -30.0 to 150.0, 25.0 to 260.0, 0.0 to 600.0)) {
                val z = pose.position.z - downrange
                val y = projection.project(x, 0.0, z).y
                val offset = (y - scene.hazeTopY) / (scene.hazeBottomY - scene.hazeTopY)
                val expected = style.haze.referenceMeters / projection.hazeDistance(x, z)
                assertThat(offset.toDouble(), "$pose at $x, $z").isCloseTo(expected, 1e-3)
            }
        }
    }

    @Test
    fun theHazeOverlayIsHiddenLookingStraightDown() {
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), RangeTheme.DAY.style, ::RecordingPathSink)
        scene.project(projection(RangeCameraPose(Vec3(0.0, 50.0, -100.0), Vec3(0.0, 0.0, -100.0))))
        assertThat(scene.hazeVisible).isFalse()
    }

    @Test
    fun theStripesAreSoftBandsThatFadeInAndOutAndMeetTheirNeighbours() {
        val style = RangeTheme.DAY.style
        val profile = style.stripeGradient
        assertThat(profile.first().color.alpha).isEqualTo(0f)
        assertThat(profile.last().color.alpha).isEqualTo(0f)
        assertThat(profile.maxOf { it.color.alpha }).isEqualTo(style.stripe.alpha)
        assertThat(profile.map { it.offset }).isEqualTo(profile.map { it.offset }.sorted())
        for ((a, b) in profile.zipWithNext()) {
            assertThat(kotlin.math.abs(a.color.alpha - b.color.alpha).toDouble()).isLessThanOrEqualTo(0.45)
        }

        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), style, ::RecordingPathSink)
        scene.project(RangeProjection(RangeCameraPlanner().pose, width = 1000f, height = 2000f))

        assertThat(scene.stripes.size).isEqualTo(11)
        assertThat(scene.stripes.all { it.gradientVisible }).isTrue()
        for (stripe in scene.stripes) {
            // Near edge low on the screen, far edge above it, straight up the screen under the tee camera.
            assertThat(stripe.startY).isGreaterThan(stripe.endY)
            assertThat(stripe.startX.toDouble()).isCloseTo(500.0, 0.01)
            assertThat(stripe.endX.toDouble()).isCloseTo(500.0, 0.01)
        }
        // Each stripe's far edge is its neighbour's near edge: no gap and no seam between them.
        for ((near, far) in scene.stripes.zipWithNext()) {
            assertThat(near.endY.toDouble()).isCloseTo(far.startY.toDouble(), 0.01)
        }
    }

    @Test
    fun aStripeUnderTheCameraStillFadesFromWhereItsNearEdgeWouldBe() {
        val scene = RangeScene(RangeSceneDescription.standard(treeCount = 0), RangeTheme.DAY.style, ::RecordingPathSink)
        // Over the second stripe (30–66 m down the range), 8 m up, looking on.
        val pose = RangeCameraPose(Vec3(0.0, 8.0, -50.0), Vec3(0.0, 0.0, -120.0))
        val projection = RangeProjection(pose, width = 1000f, height = 2000f)
        scene.project(projection)

        val stripe = scene.stripes[1]
        assertThat(stripe.gradientVisible).isTrue()
        // Its far edge, 66 m down the range, is where the gradient ends.
        val farEdge = projection.project(0.0, 0.0, -66.0)
        assertThat(stripe.endY.toDouble()).isCloseTo(farEdge.y.toDouble(), 0.5)
        // Its near edge is behind the camera, so the gradient starts below the canvas.
        assertThat(stripe.startY).isGreaterThan(2000f)
        // Stripes behind the camera have no gradient to draw.
        assertThat(scene.stripes[0].gradientVisible).isFalse()
    }

    // Plan F8a2p: labels and far markers under the overlaid UI.

    @Test
    fun obstructionsOverlapOnlyWhereTheyCover() {
        val obstructions = RangeObstructions()
        assertThat(obstructions.set(floatArrayOf(10f, 20f, 110f, 70f, 500f, 500f, 600f, 600f))).isTrue()
        assertThat(obstructions.set(floatArrayOf(10f, 20f, 110f, 70f, 500f, 500f, 600f, 600f))).isFalse()
        assertThat(obstructions.count).isEqualTo(2)

        assertThat(obstructions.intersects(100f, 60f, 150f, 90f)).isTrue()
        assertThat(obstructions.intersects(550f, 550f, 560f, 560f)).isTrue()
        assertThat(obstructions.intersects(0f, 0f, 1000f, 1000f)).isTrue()
        // Touching an edge, or clear of both.
        assertThat(obstructions.intersects(110f, 20f, 200f, 70f)).isFalse()
        assertThat(obstructions.intersects(200f, 200f, 300f, 300f)).isFalse()
        assertThat(RangeObstructions().intersects(0f, 0f, 1000f, 1000f)).isFalse()
    }

    @Test
    fun aLabelUnderTheOverlaidUiIsHiddenAndItsMarkerFaded() {
        val style = RangeTheme.DAY.style
        val frame = RangeFrame(style, clubPaletteSize = 8, newPath = ::RecordingPathSink)
        val pose = RangeCameraPlanner().pose
        frame.resize(1000f, 2000f, pose)
        frame.setLabelPixels(minPixels = 27f, maxPixels = 48f)
        frame.prepare(pose, progress = 0f)
        val scene = frame.scene
        val drawn = scene.labels.filter { it.drawn }
        assertThat(drawn.size).isGreaterThan(2)
        val covered = drawn.first()
        val clear = drawn.last()
        val marker = scene.polygons[2 * scene.labels.indexOf(covered)]
        val markerArgb = marker.argb

        // A card over the first label and its marker, only.
        frame.setObstructions(
            floatArrayOf(covered.anchorX - 5f, covered.anchorY - 5f, covered.anchorX + 5f, marker.bottom),
        )
        frame.prepare(pose, progress = 0f)

        assertThat(covered.obstructed).isTrue()
        assertThat(covered.drawn).isFalse()
        assertThat(clear.drawn).isTrue()
        assertThat(
            marker.argb ushr 24,
        ).isEqualTo(((markerArgb ushr 24) * RangeScene.OBSTRUCTED_MARKER_ALPHA + 0.5f).toInt())
        assertThat(marker.argb and 0xFFFFFF).isEqualTo(markerArgb and 0xFFFFFF)

        // The card goes away: the label and the marker come back, with no re-projection.
        frame.setObstructions(FloatArray(0))
        frame.prepare(pose, progress = 0f)
        assertThat(covered.drawn).isTrue()
        assertThat(marker.argb).isEqualTo(markerArgb)
    }

    @Test
    fun aLabelBoxIsMeasuredAtItsFontSize() {
        val label = WorldLabel("250", 0.0, 2.0, -92.0)
        label.project(projection(), SceneScratch(capacity = 8))
        val obstructions = RangeObstructions()
        // Clear of the text's half width (3 digits at 0.62 em plus padding, at 20 px) to the right.
        val halfWidth = 20f * (WorldLabel.GLYPH_WIDTH_EM * 3 + WorldLabel.PADDING_EM) / 2
        obstructions.set(
            floatArrayOf(label.anchorX + halfWidth + 1f, label.anchorY - 30f, label.anchorX + 80f, label.anchorY),
        )
        label.obstruct(obstructions, fontPixels = 20f)
        assertThat(label.obstructed).isFalse()

        label.obstruct(obstructions, fontPixels = 40f)
        assertThat(label.obstructed).isTrue()
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
        // Plan F8a2p: the fairway, one polygon from just behind the tee to the range's end.
        assertThat(scene.fairway.visible).isTrue()
        assertThat(scene.fairway.color).isEqualTo(RangeTheme.DAY.style.fairway)
        assertThat(
            scene.fairway.path.commands
                .count { it == "moveTo" },
        ).isEqualTo(1)
        assertThat(scene.groundPolygons.all { it.visible }).isTrue()
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
