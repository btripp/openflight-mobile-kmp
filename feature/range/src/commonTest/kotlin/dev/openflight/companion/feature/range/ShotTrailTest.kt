// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Plan F8a2t: the shot trail styles, their earlier trails and landing effects, written by the shared
 * [ShotTrail] (so both renderers fill the same outlines). A 1000 x 1000 px canvas behind the tee
 * looking down the range, and a 4 s arc 2 m right of the target line that slows as it flies.
 */
class ShotTrailTest {
    private val pose = RangeCameraPose(Vec3(0.0, 2.0, 8.0), Vec3(0.0, 2.0, -100.0), verticalFovDegrees = 90.0)
    private val projection = RangeProjection(pose, width = 1000f, height = 1000f)
    private val day = RangeTheme.DAY.style

    @Test
    fun classicIsTodaysTracerExactly() {
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val today = TracerRibbon(RecordingPathSink(), RecordingPathSink(), day.tracerGlowWidthFactor)
        today.ensureCapacity(SEGMENTS)
        today.build(geometry, geometry.sampleAt(0.5f))

        val trail = trail(ShotTrailStyle.CLASSIC, geometry, progress = 0.5f)

        val glow = trail.styleLayer(0)
        val core = trail.styleLayer(1)
        assertThat(glow.path.points).isEqualTo(today.glow!!.points)
        assertThat(core.path.points).isEqualTo(today.path.points)
        assertThat(glow.argb).isEqualTo(day.tracerGlow.toArgb())
        assertThat(core.argb).isEqualTo(day.tracer.toArgb())
        assertThat(trail.ribbon.tipX).isEqualTo(today.tipX)
        assertThat(trail.visibleStyleLayers()).isEqualTo(2)
    }

    @Test
    fun everyStyleFillsItsOwnClosedOutlinesWithinTheLayerBudget() {
        val expected =
            mapOf(
                ShotTrailStyle.CLASSIC to 2,
                ShotTrailStyle.BROADCAST_GLOW to 3,
                ShotTrailStyle.CLUB_COLOUR to 2,
                ShotTrailStyle.DOTTED to 2,
                ShotTrailStyle.NEON to 3,
                ShotTrailStyle.SPIN_RIBBON to 2,
                ShotTrailStyle.GROUND_TRACK to 3,
                ShotTrailStyle.RAINBOW to ShotTrail.RAINBOW_BANDS,
                // The glow at the head, then the tail's alpha bands.
                ShotTrailStyle.COMET to 1 + ShotTrail.COMET_BANDS,
                // The age bands, then the core at the ball.
                ShotTrailStyle.SMOKE to ShotTrail.SMOKE_BANDS + 1,
            )
        for (style in ShotTrailStyle.entries) {
            val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
            val trail = trail(style, geometry, progress = 1f)
            val visible = trail.visibleStyleLayers()
            expected[style]?.let { assertThat(visible, style.name).isEqualTo(it) }
            assertThat(visible, style.name).isBetween(1, ShotTrail.STYLE_LAYERS)
            for (index in 0 until ShotTrail.STYLE_LAYERS) {
                val layer = trail.styleLayer(index)
                if (!layer.visible) continue
                val commands = layer.path.commands
                assertThat(commands.first(), style.name).isEqualTo("rewind")
                assertThat(commands.count { it == "moveTo" }, style.name).isEqualTo(commands.count { it == "close" })
                assertThat(commands.last(), style.name).isEqualTo("close")
            }
            // Only the club colour asks for the palette.
            val palette = (0 until ShotTrail.STYLE_LAYERS).map { trail.styleLayer(it) }.filter { it.visible }
            val usesPalette = palette.all { it.paletteIndex == CLUB_INDEX }
            assertThat(usesPalette, style.name).isEqualTo(style == ShotTrailStyle.CLUB_COLOUR)
        }
    }

    @Test
    fun theHeatColourGetsHotterAsTheBallGetsFaster() {
        val hues = (10..90 step 5).map { hue(ShotTrail.heatColor(it.toFloat())) }

        for (index in 1 until hues.size) assertThat(hues[index]).isLessThanOrEqualTo(hues[index - 1])
        assertThat(hues.first()).isCloseTo(ShotTrail.HEAT_COOL_HUE.toDouble(), 1.0)
        assertThat(hues.last()).isCloseTo(ShotTrail.HEAT_HOT_HUE.toDouble(), 1.0)
        val slow = ShotTrail.heatColor(ShotTrail.HEAT_MIN_SPEED)
        val fast = ShotTrail.heatColor(ShotTrail.HEAT_MAX_SPEED)
        assertThat(slow.blue).isGreaterThan(slow.red)
        assertThat(fast.red).isGreaterThan(fast.blue)
        for (speed in 10..90) {
            assertThat(
                ShotTrail.heatFraction(speed + 1f),
            ).isGreaterThanOrEqualTo(ShotTrail.heatFraction(speed.toFloat()))
        }
    }

    @Test
    fun speedHeatPaintsTheFastLaunchHotterThanTheSlowApex() {
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val trail = trail(ShotTrailStyle.SPEED_HEAT, geometry, progress = 1f)

        // The launch (sample 0) is the fastest point and the apex (mid-flight) slower.
        val launchBand = ShotTrail.heatBand(geometry.speeds[0])
        val apexBand = ShotTrail.heatBand(geometry.speeds[SEGMENTS / 2])
        assertThat(geometry.speeds[0]).isGreaterThan(geometry.speeds[SEGMENTS / 2])
        assertThat(launchBand).isGreaterThan(apexBand)
        assertThat(trail.styleLayer(launchBand).visible).isTrue()
        assertThat(trail.styleLayer(apexBand).visible).isTrue()
        assertThat(hue(RangeColor.fromArgb(trail.styleLayer(launchBand).argb.toLong() and 0xFFFFFFFFL)))
            .isLessThan(hue(RangeColor.fromArgb(trail.styleLayer(apexBand).argb.toLong() and 0xFFFFFFFFL)))
    }

    @Test
    fun beadsAreEvenlySpacedInTime() {
        val flight = arc()
        val geometry = FlightGeometry.build(flight, projection, SEGMENTS)
        val trail = trail(ShotTrailStyle.DOTTED, geometry, progress = 1f)

        val centres = circleCentres(trail.styleLayer(1).path)
        // One bead every DOTTED_EVERY samples, the tee's included, up to the landing.
        assertThat(centres.size).isEqualTo(SEGMENTS / ShotTrail.DOTTED_EVERY + 1)
        val step = flight.flightTime * ShotTrail.DOTTED_EVERY / SEGMENTS
        centres.forEachIndexed { bead, (x, y) ->
            val position = flight.point(step * bead)!!.positionMeters
            val scene = RangeProjection.flightToScene(position)
            val expected = projection.project(scene.x, scene.y, scene.z)
            assertThat(x.toDouble()).isCloseTo(expected.x.toDouble(), 0.01)
            assertThat(y.toDouble()).isCloseTo(expected.y.toDouble(), 0.01)
        }
        // The ball slows, so equal times cover less and less ground: the beads bunch up.
        val first = distance(flight, 0.0, step)
        val last = distance(flight, step * (centres.size - 2), step * (centres.size - 1))
        assertThat(last).isLessThan(first)
    }

    @Test
    fun theRibbonTwistsInProportionToSpin() {
        assertThat(ShotTrail.twistTurns(2_400.0, 1.0)).isCloseTo(1.0, 1e-9)
        assertThat(ShotTrail.twistTurns(4_800.0, 1.0)).isCloseTo(2 * ShotTrail.twistTurns(2_400.0, 1.0), 1e-9)
        assertThat(ShotTrail.twistTurns(9_000.0, 5.0)).isCloseTo(ShotTrail.twistTurns(9_000.0, 1.0) * 5, 1e-9)

        // Each half turn flips the face shown: the pieces alternate between the two tones.
        val slow = faceChanges(spinRpm = 2_400.0)
        val fast = faceChanges(spinRpm = 4_800.0)
        // 4 s of flight: 4 turns (8 half turns) at 2 400 rpm, 8 turns at twice the spin.
        assertThat(slow).isBetween(7, 9)
        assertThat(fast).isBetween(2 * slow - 2, 2 * slow + 2)
    }

    @Test
    fun theCometFadesAndThinsTowardItsTailAndStopsBehindIt() {
        val colors = ShotTrailPalette(day).styleLayers(ShotTrailStyle.COMET)
        val alphas = (1..ShotTrail.COMET_BANDS).map { colors[it] ushr ALPHA_SHIFT }
        for (index in 1 until alphas.size) assertThat(alphas[index]).isGreaterThan(alphas[index - 1])

        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val comet = trail(ShotTrailStyle.COMET, geometry, progress = 1f)
        val classic = trail(ShotTrailStyle.CLASSIC, geometry, progress = 1f)
        // The comet's tail ends well down the flight: the tee end of the classic tracer is nearer the camera.
        val cometTail =
            comet
                .styleLayer(1)
                .path.points
                .first()
        val classicTee =
            classic
                .styleLayer(1)
                .path.points
                .first()
        assertThat(abs(cometTail.second - classicTee.second)).isGreaterThan(1f)
    }

    @Test
    fun smokeWidensAndFadesWithAge() {
        val colors = ShotTrailPalette(day).styleLayers(ShotTrailStyle.SMOKE)
        val alphas = (0 until ShotTrail.SMOKE_BANDS).map { colors[it] ushr ALPHA_SHIFT }
        for (index in 1 until alphas.size) assertThat(alphas[index]).isLessThan(alphas[index - 1])

        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val smoke = trail(ShotTrailStyle.SMOKE, geometry, progress = 1f)
        val classic = trail(ShotTrailStyle.CLASSIC, geometry, progress = 1f)
        // At the tee the smoke is the oldest: 1.1 × (1 + 5) times the tracer's width.
        val oldest = smoke.styleLayer(ShotTrail.SMOKE_BANDS - 1).path.firstWidth()
        val tracer = classic.styleLayer(1).path.firstWidth()
        assertThat(oldest / tracer).isCloseTo(6.6, 0.01)
    }

    @Test
    fun rainbowRunsItsHueAlongTheLength() {
        val colors = ShotTrailPalette(day).styleLayers(ShotTrailStyle.RAINBOW)
        val hues = colors.map { hue(RangeColor.fromArgb(it.toLong() and 0xFFFFFFFFL)) }
        for (index in 1 until hues.size) assertThat(hues[index]).isGreaterThan(hues[index - 1])

        // Half way through the flight only the first half of the hues are drawn.
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val half = trail(ShotTrailStyle.RAINBOW, geometry, progress = 0.5f)
        assertThat(half.visibleStyleLayers()).isEqualTo(ShotTrail.RAINBOW_BANDS / 2)
    }

    @Test
    fun theGroundTrackRunsBelowTheTracer() {
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val trail = trail(ShotTrailStyle.GROUND_TRACK, geometry, progress = 1f)

        val ground = trail.styleLayer(0).path.points
        val tracer = trail.styleLayer(2).path.points
        assertThat(ground.map { it.second }.average()).isGreaterThan(tracer.map { it.second }.average())
        assertThat(trail.styleLayer(0).argb ushr ALPHA_SHIFT).isLessThan(trail.styleLayer(2).argb ushr ALPHA_SHIFT)
    }

    @Test
    fun theClubColourTrailUsesTheShotsPaletteIndex() {
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val trail = trail(ShotTrailStyle.CLUB_COLOUR, geometry, progress = 1f)

        assertThat(trail.styleLayer(0).paletteIndex).isEqualTo(CLUB_INDEX)
        assertThat(trail.styleLayer(1).paletteIndex).isEqualTo(CLUB_INDEX)
        assertThat(trail.styleLayer(1).argb ushr ALPHA_SHIFT).isGreaterThan(trail.styleLayer(0).argb ushr ALPHA_SHIFT)
    }

    @Test
    fun keepLastDrawsAtMostThreeEarlierTrailsFadingWithAge() {
        assertThat(ShotTrail.PRIOR_FADES.size).isEqualTo(ShotTrail.PRIOR_LAYERS)
        for (index in 1 until ShotTrail.PRIOR_FADES.size) {
            assertThat(ShotTrail.PRIOR_FADES[index]).isLessThan(ShotTrail.PRIOR_FADES[index - 1])
        }
        val frame = frame()
        val priors = List(4) { ActiveFlight(arc(lateral = it * 5.0), playbackId = it.toLong()) }

        frame.setTrail(RangeTrailState(keepLast = 3, priorFlights = priors), staticEffects = false)
        frame.prepare(RangeCameraPlanner().pose, progress = 1f)

        val layers = frame.trail.layers.take(ShotTrail.PRIOR_LAYERS)
        assertThat(layers.all { it.visible }).isTrue()
        val alphas = layers.map { it.argb ushr ALPHA_SHIFT }
        assertThat(alphas).containsExactly(
            *ShotTrail.PRIOR_FADES.map { (FULL_PRIOR_ALPHA * it + 0.5f).toInt() }.toTypedArray(),
        )

        frame.setTrail(RangeTrailState(keepLast = 0), staticEffects = false)
        frame.prepare(RangeCameraPlanner().pose, progress = 1f)
        assertThat(
            frame.trail.layers
                .take(ShotTrail.PRIOR_LAYERS)
                .none { it.visible },
        ).isTrue()
    }

    @Test
    fun theLandingEffectShowsOnlyOnceLandedAndGrowsUnlessMotionIsReduced() {
        val frame = frame()
        frame.setFlight(ActiveFlight(arc(), playbackId = 1))
        val ring = frame.trail.layers[ShotTrail.EFFECT_START]

        frame.setTrail(RangeTrailState(landingEffect = LandingEffect.RING), staticEffects = false)
        frame.prepare(RangeCameraPlanner().pose, progress = 0.9f)
        assertThat(ring.visible).isFalse()

        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.1f)
        assertThat(ring.visible).isTrue()
        val early = ring.path.spanX()
        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.8f)
        val late = ring.path.spanX()
        assertThat(late).isGreaterThan(early)

        // Reduced motion: one static frame whatever the time since landing.
        frame.setTrail(RangeTrailState(landingEffect = LandingEffect.RING), staticEffects = true)
        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.1f)
        val staticEarly = ring.path.points.toList()
        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.8f)
        assertThat(ring.path.points).isEqualTo(staticEarly)

        frame.setTrail(RangeTrailState(landingEffect = LandingEffect.BURST), staticEffects = false)
        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.4f)
        val sparkles = frame.trail.layers[ShotTrail.EFFECT_START + 1]
        assertThat(sparkles.visible).isTrue()
        assertThat(sparkles.path.commands.count { it == "moveTo" }).isEqualTo(SPARKLES)

        frame.setTrail(RangeTrailState(landingEffect = LandingEffect.OFF), staticEffects = false)
        frame.prepare(RangeCameraPlanner().pose, progress = 1f, landedSeconds = 0.4f)
        assertThat(ring.visible || sparkles.visible).isFalse()
    }

    @Test
    fun theOverlayKeepsItsThinTrailsWhateverTheStyle() {
        val frame = frame()
        frame.setTrail(RangeTrailState(style = ShotTrailStyle.SMOKE), staticEffects = false)
        frame.setOverlay(listOf(OverlayFlight("a", "driver", colorIndex = 0, trajectory = arc())), selectedId = null)

        frame.prepare(RangeCameraPlanner().pose, progress = 1f)

        assertThat(
            frame.overlay!!
                .groupPaths
                .single()
                .commands
                .count { it == "lineTo" },
        ).isGreaterThan(0)
        assertThat(frame.trail.layers.none { it.visible }).isTrue()
    }

    @Test
    fun everyStylesHeadStaysReadableOnEveryTheme() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            val palette = ShotTrailPalette(style)
            for (trail in ShotTrailStyle.entries - ShotTrailStyle.CLUB_COLOUR) {
                val head = palette.head(trail)
                for (background in listOf(style.sky.last().color, style.fairway, style.ground)) {
                    val seen = over(head, background)
                    assertThat(colorDistance(seen, background), "${theme.name} ${trail.name}")
                        .isGreaterThan(MIN_HEAD_DISTANCE)
                }
            }
        }
    }

    // region Helpers

    private fun trail(
        style: ShotTrailStyle,
        geometry: FlightGeometry,
        progress: Float,
        spinRpm: Double = 3_000.0,
    ): ShotTrail<RecordingPathSink> {
        val trail = ShotTrail(day, ::RecordingPathSink)
        trail.ensureCapacity(geometry.segments)
        trail.setOptions(style, LandingEffect.OFF, staticEffects = false)
        trail.setFlight(spinRpm, CLUB_INDEX)
        trail.build(geometry, geometry.sampleAt(progress))
        return trail
    }

    private fun frame(): RangeFrame<RecordingPathSink> {
        val frame = RangeFrame(day, clubPaletteSize = 8, newPath = ::RecordingPathSink)
        frame.resize(1000f, 2000f, RangeCameraPlanner().pose)
        return frame
    }

    private fun ShotTrail<RecordingPathSink>.styleLayer(index: Int) = layers[ShotTrail.STYLE_START + index]

    private fun ShotTrail<RecordingPathSink>.visibleStyleLayers(): Int =
        (0 until ShotTrail.STYLE_LAYERS).count { styleLayer(it).visible }

    /** How many times the spin ribbon changes face over the whole flight. */
    private fun faceChanges(spinRpm: Double): Int {
        val geometry = FlightGeometry.build(arc(), projection, SEGMENTS)
        val trail = trail(ShotTrailStyle.SPIN_RIBBON, geometry, progress = 1f, spinRpm = spinRpm)
        val pieces =
            (0..1).sumOf { face ->
                trail
                    .styleLayer(face)
                    .path.commands
                    .count { it == "moveTo" }
            }
        return pieces - 1
    }

    /** The centre of each 12-gon written into [sink]. */
    private fun circleCentres(sink: RecordingPathSink): List<Pair<Float, Float>> =
        sink.points.chunked(CIRCLE_VERTICES).map { ring ->
            ring.map { it.first }.average().toFloat() to ring.map { it.second }.average().toFloat()
        }

    /** The width of the first ribbon in [this] at its first point (the first and last outline points). */
    private fun RecordingPathSink.firstWidth(): Double {
        val closeAt = commands.indexOf("close")
        val outline = points.take(closeAt - 1)
        return hypot(
            (outline.first().first - outline.last().first).toDouble(),
            (outline.first().second - outline.last().second).toDouble(),
        )
    }

    private fun RecordingPathSink.spanX(): Float = points.maxOf { it.first } - points.minOf { it.first }

    private fun distance(
        flight: FlightTrajectory,
        from: Double,
        to: Double,
    ): Double {
        val a = flight.point(from)!!.positionMeters
        val b = flight.point(to)!!.positionMeters
        return sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
    }

    /** [top] (straight alpha) seen over an opaque [bottom]. */
    private fun over(
        top: RangeColor,
        bottom: RangeColor,
    ): RangeColor =
        RangeColor(
            top.red * top.alpha + bottom.red * (1 - top.alpha),
            top.green * top.alpha + bottom.green * (1 - top.alpha),
            top.blue * top.alpha + bottom.blue * (1 - top.alpha),
        )

    private fun colorDistance(
        a: RangeColor,
        b: RangeColor,
    ): Double {
        val dr = (a.red - b.red).toDouble()
        val dg = (a.green - b.green).toDouble()
        val db = (a.blue - b.blue).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** The hue of [color] in degrees, 0..360. */
    private fun hue(color: RangeColor): Double {
        val r = color.red.toDouble()
        val g = color.green.toDouble()
        val b = color.blue.toDouble()
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        if (chroma == 0.0) return 0.0
        val sector =
            when (max) {
                r -> ((g - b) / chroma).mod(6.0)
                g -> (b - r) / chroma + 2
                else -> (r - g) / chroma + 4
            }
        return sector * 60
    }

    /**
     * A [seconds]-long flight [lateral] m right of the line: launched at 60 m/s forward, slowing
     * with drag (e-folding 1 / 0.25 s), over a symmetric arc that lands at the end.
     */
    private fun arc(
        seconds: Double = 4.0,
        lateral: Double = 2.0,
    ): FlightTrajectory {
        val drag = 0.25
        val forward = 60.0
        val up = 4.905 * seconds
        val points =
            (0..(seconds * 20).toInt()).map { step ->
                val t = step / 20.0
                FlightPoint(
                    time = t,
                    positionMeters = Vec3(lateral, up * t - 4.905 * t * t, forward / drag * (1 - exp(-drag * t))),
                    velocityMetersPerSecond = Vec3(0.0, up - 9.81 * t, forward * exp(-drag * t)),
                )
            }
        return FlightTrajectory(eventId = "arc-$lateral", points = points, provenance = FlightInputProvenance())
    }

    private companion object {
        const val SEGMENTS = 72
        const val CLUB_INDEX = 5
        const val ALPHA_SHIFT = 24
        const val CIRCLE_VERTICES = 12
        const val SPARKLES = 12
        const val MIN_HEAD_DISTANCE = 0.3

        /** An earlier trail's alpha before its fade: 0.85, packed (217 of 255). */
        const val FULL_PRIOR_ALPHA = 217f
    }

    // endregion
}
