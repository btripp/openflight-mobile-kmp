// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.ShotTrailStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One filled outline of the shot trail (plan F8a2t): the platform fills [path] (non-zero winding)
 * with [argb] (`0xAARRGGBB`), or, when [paletteIndex] is 0 or more, with that club palette colour
 * (`OfClubPalette` / `Theme.clubColors`) at [argb]'s alpha. Invisible layers are skipped.
 */
class TrailLayer<P : PathSink> internal constructor(
    val path: P,
) {
    var argb: Int = 0
        internal set
    var paletteIndex: Int = NO_PALETTE
        internal set
    var visible: Boolean = false
        internal set

    internal fun reset(
        argb: Int,
        paletteIndex: Int = NO_PALETTE,
    ) {
        path.rewind()
        this.argb = argb
        this.paletteIndex = paletteIndex
        visible = false
    }

    internal fun hide() {
        path.rewind()
        visible = false
    }

    companion object {
        /** [paletteIndex] when the layer is plain [argb]. */
        const val NO_PALETTE = -1
    }
}

/**
 * The live shot's trail in one of the [ShotTrailStyle]s (plan F8a2t), its faded earlier trails
 * ("Keep last shots") and the [LandingEffect], all written as filled outlines into [layers] from the
 * shared flight geometry, so both renderers draw exactly the same thing. The platform fills every
 * visible layer in order, then draws the ball at the [ribbon]'s tip:
 *
 * 1. [PRIOR_LAYERS] earlier trails, newest first, each one ribbon faded by [PRIOR_FADES];
 * 2. [EFFECT_LAYERS] landing-effect outlines (only once the ball has landed);
 * 3. up to [STYLE_LAYERS] outlines of the style itself (bands of colour or alpha along the trail,
 *    glows under cores, beads, a ground line).
 *
 * The overlay's 200-shot view never goes through here: it keeps its thin per-club strokes.
 *
 * Everything is preallocated for [ensureCapacity]'s segment count: [build], [buildPriors] and
 * [buildLandingEffect] rewrite the same paths and arrays and allocate nothing.
 */
@Suppress("TooManyFunctions", "LargeClass") // One small, allocation-free writer per style.
class ShotTrail<P : PathSink>(
    style: RangeVisualStyle,
    newPath: () -> P,
) {
    /** Every layer in drawing order: the earlier trails, the landing effect, then the style's. */
    val layers: List<TrailLayer<P>> = List(LAYER_COUNT) { TrailLayer(newPath()) }

    private val palette = ShotTrailPalette(style)

    /** Collects the live trail's visible runs and hands them to the style's painter. */
    val ribbon: TracerRibbon<P> = TracerRibbon(newPath(), painter = TracerRunPainter { paintRun(it) })

    var trailStyle: ShotTrailStyle = ShotTrailStyle.DEFAULT
        private set
    var landingEffect: LandingEffect = LandingEffect.DEFAULT
        private set
    private var staticEffects = false

    private var spinRpm = DEFAULT_SPIN_RPM
    private var paletteIndex = 0
    private var segments = 1
    private var flightTime = 0.0
    private var tipPosition = 0f
    private var geometry: FlightGeometry? = null

    /** Scratch: the subdivided run of [ShotTrailStyle.SPIN_RIBBON] and the ground line's run. */
    private val subRun = TracerRun()
    private val groundRun = TracerRun()
    private var bands = IntArray(0)
    private val point = FloatArray(2)

    /** The earlier trails' geometry and colour, newest first. */
    private val priorGeometries = arrayOfNulls<FlightGeometry>(PRIOR_LAYERS)
    private val priorPalettes = IntArray(PRIOR_LAYERS)
    private var priorWriting = 0
    private val priorRibbon: TracerRibbon<P> =
        TracerRibbon(newPath(), painter = TracerRunPainter { paintPrior(it) })

    /** Sizes the scratch arrays for flights of [segments] segments. */
    fun ensureCapacity(segments: Int) {
        ribbon.ensureCapacity(segments)
        priorRibbon.ensureCapacity(segments)
        val points = segments + RUN_EXTRA_POINTS
        subRun.ensureCapacity(points * SPIN_SUBDIVISIONS)
        groundRun.ensureCapacity(points)
        if (bands.size < points * SPIN_SUBDIVISIONS) bands = IntArray(points * SPIN_SUBDIVISIONS)
    }

    /** Picks the style and landing effect; [staticEffects] (reduced motion) freezes the effect. */
    fun setOptions(
        style: ShotTrailStyle,
        effect: LandingEffect,
        staticEffects: Boolean,
    ) {
        trailStyle = style
        landingEffect = effect
        this.staticEffects = staticEffects
    }

    /** The live flight's spin (for [ShotTrailStyle.SPIN_RIBBON]) and club colour (for [ShotTrailStyle.CLUB_COLOUR]). */
    fun setFlight(
        spinRpm: Double?,
        clubColorIndex: Int,
    ) {
        this.spinRpm = spinRpm?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_SPIN_RPM
        paletteIndex = clubColorIndex.coerceAtLeast(0)
    }

    /**
     * The earlier trails to keep, newest first (at most [PRIOR_LAYERS]), each with its club colour
     * index; drawn by [buildPriors].
     */
    fun setPriors(
        geometries: List<FlightGeometry>,
        clubColorIndices: List<Int>,
    ) {
        for (index in 0 until PRIOR_LAYERS) {
            priorGeometries[index] = geometries.getOrNull(index)
            priorPalettes[index] = clubColorIndices.getOrNull(index) ?: 0
        }
    }

    /** Rewrites the earlier trails for the current camera (their geometry must be reprojected). */
    fun buildPriors() {
        for (index in 0 until PRIOR_LAYERS) {
            val layer = layers[index]
            val prior = priorGeometries[index]
            if (prior == null) {
                layer.hide()
                continue
            }
            val base = palette.prior(trailStyle)
            val alpha = ((base ushr ALPHA_SHIFT) / CHANNEL_MAX) * PRIOR_FADES[index]
            val usesPalette = trailStyle == ShotTrailStyle.CLUB_COLOUR
            layer.reset(withAlpha(base, alpha), if (usesPalette) priorPalettes[index] else TrailLayer.NO_PALETTE)
            priorWriting = index
            priorRibbon.build(prior, prior.segments.toFloat())
        }
    }

    /** No live trail (and no landing effect); the earlier trails stay. */
    fun clear() {
        geometry = null
        for (index in PRIOR_LAYERS until LAYER_COUNT) layers[index].hide()
    }

    /** The live trail of [geometry] from the tee up to fractional sample [at]. */
    fun build(
        geometry: FlightGeometry,
        at: Float,
    ) {
        this.geometry = geometry
        segments = geometry.segments
        flightTime = geometry.flightTime
        tipPosition = at
        resetStyleLayers()
        ribbon.build(geometry, at)
        when (trailStyle) {
            ShotTrailStyle.DOTTED -> writeBeads(geometry, at)
            ShotTrailStyle.GROUND_TRACK -> writeGroundTrack(geometry, at)
            else -> Unit
        }
    }

    /**
     * The landing effect around [geometry]'s landing, [landedSeconds] after touchdown (the
     * follow camera's settle time long; static at [STATIC_EFFECT_PHASE] under reduced motion).
     * Hidden before the ball lands ([landed] false) or when the effect is off.
     */
    fun buildLandingEffect(
        geometry: FlightGeometry,
        projection: RangeProjection,
        landed: Boolean,
        landedSeconds: Float,
    ) {
        val ring = layers[EFFECT_START]
        val detail = layers[EFFECT_START + 1]
        ring.hide()
        detail.hide()
        if (!landed || landingEffect == LandingEffect.OFF) return
        val phase =
            if (staticEffects) STATIC_EFFECT_PHASE else (landedSeconds / LANDING_EFFECT_SECONDS).coerceIn(0f, 1f)
        val eased = easeOut(phase)
        val landing = geometry.landing
        when (landingEffect) {
            LandingEffect.RING -> {
                ring.reset(withAlpha(palette.ringArgb, RING_ALPHA * (1 - RING_FADE * phase)))
                val outer = RING_START_METERS + (RING_END_METERS - RING_START_METERS) * eased
                ring.visible =
                    writeAnnulus(ring.path, projection, landing.x, landing.z, outer, outer - RING_WIDTH_METERS)
                val lagged = easeOut(((phase - RING_LAG) / (1 - RING_LAG)).coerceIn(0f, 1f))
                val inner = RING_START_METERS + (RING_INNER_END_METERS - RING_START_METERS) * lagged
                detail.reset(withAlpha(palette.ringInnerArgb, RING_INNER_ALPHA * (1 - RING_FADE * phase)))
                detail.visible =
                    writeAnnulus(detail.path, projection, landing.x, landing.z, inner, inner - RING_WIDTH_METERS)
            }

            LandingEffect.BURST -> {
                val radius = DUST_START_METERS + (DUST_END_METERS - DUST_START_METERS) * eased
                ring.reset(withAlpha(palette.dustArgb, DUST_ALPHA * (1 - DUST_FADE * phase)))
                ring.visible = writeAnnulus(ring.path, projection, landing.x, landing.z, radius, 0.0)
                detail.reset(withAlpha(palette.sparkleArgb, SPARKLE_ALPHA * (1 - SPARKLE_FADE * phase)))
                detail.visible = writeSparkles(detail.path, projection, landing.x, landing.z, phase, eased)
            }

            LandingEffect.OFF -> {
                Unit
            }
        }
    }

    // region The live trail's styles.

    private fun resetStyleLayers() {
        val colors = palette.styleLayers(trailStyle)
        for (index in 0 until STYLE_LAYERS) {
            val layer = layers[STYLE_START + index]
            if (index >= colors.size) {
                layer.hide()
            } else {
                val usesPalette = trailStyle == ShotTrailStyle.CLUB_COLOUR
                layer.reset(colors[index], if (usesPalette) paletteIndex else TrailLayer.NO_PALETTE)
            }
        }
    }

    /** One visible run of the live trail, painted in the current style. */
    private fun paintRun(run: TracerRun) {
        when (trailStyle) {
            ShotTrailStyle.CLASSIC, ShotTrailStyle.GROUND_TRACK -> {
                val first = if (trailStyle == ShotTrailStyle.GROUND_TRACK) 1 else 0
                write(first, run, palette.glowWidthFactor)
                write(first + 1, run, 1f)
            }

            ShotTrailStyle.BROADCAST_GLOW -> {
                write(0, run, BROADCAST_OUTER_WIDTH)
                write(1, run, BROADCAST_INNER_WIDTH)
                write(2, run, BROADCAST_CORE_WIDTH)
            }

            ShotTrailStyle.CLUB_COLOUR -> {
                write(0, run, CLUB_GLOW_WIDTH)
                write(1, run, 1f)
            }

            ShotTrailStyle.NEON -> {
                write(0, run, NEON_HALO_WIDTH)
                write(1, run, NEON_TUBE_WIDTH)
                write(2, run, NEON_CORE_WIDTH)
            }

            ShotTrailStyle.DOTTED -> {
                write(0, run, DOTTED_THREAD_WIDTH)
            }

            ShotTrailStyle.COMET -> {
                paintComet(run)
            }

            ShotTrailStyle.SMOKE -> {
                paintSmoke(run)
            }

            ShotTrailStyle.SPEED_HEAT -> {
                for (k in 0 until run.size) bands[k] = heatBand(speedAt(run.positions[k]))
                writeBands(0, run, BANDED_WIDTH)
            }

            ShotTrailStyle.RAINBOW -> {
                for (k in 0 until run.size) {
                    bands[k] = bandOf(run.positions[k] / segments, RAINBOW_BANDS)
                }
                writeBands(0, run, BANDED_WIDTH)
            }

            ShotTrailStyle.SPIN_RIBBON -> {
                paintSpin(run)
            }
        }
    }

    /** The tail fades (and thins) over [COMET_LENGTH] of the flight behind the ball; a glow rides the head. */
    private fun paintComet(run: TracerRun) {
        for (k in 0 until run.size) {
            val key = cometKey(run.positions[k])
            run.widthFactors[k] = COMET_TAIL_WIDTH + (1 - COMET_TAIL_WIDTH) * key.coerceIn(0f, 1f)
            bands[k] = if (key > COMET_HEAD) 0 else NO_BAND
        }
        writeBands(0, run, palette.glowWidthFactor)
        for (k in 0 until run.size) {
            val key = cometKey(run.positions[k])
            run.widthFactors[k] = COMET_TAIL_WIDTH + (1 - COMET_TAIL_WIDTH) * key.coerceIn(0f, 1f)
            bands[k] = if (key <= 0f) NO_BAND else bandOf(key, COMET_BANDS)
        }
        writeBands(1, run, 1f)
    }

    /** Older smoke is wider and fainter; a thin core stays at the ball. */
    private fun paintSmoke(run: TracerRun) {
        for (k in 0 until run.size) {
            val age = smokeAge(run.positions[k])
            run.widthFactors[k] = 1 + SMOKE_SPREAD * age
            bands[k] = bandOf(age, SMOKE_BANDS)
        }
        writeBands(0, run, SMOKE_WIDTH)
        for (k in 0 until run.size) {
            run.widthFactors[k] = 1f
            bands[k] = if (smokeAge(run.positions[k]) < SMOKE_CORE_AGE) 0 else NO_BAND
        }
        writeBands(SMOKE_BANDS, run, SMOKE_CORE_WIDTH)
    }

    /**
     * A flat ribbon seen edge-on as it twists: [SPIN_SUBDIVISIONS] points per sample, its width
     * following |cos| of the twist angle ([twistTurns] of the spin over the flight time so far), the
     * front face and the back face in two tones.
     */
    private fun paintSpin(run: TracerRun) {
        val sub = subRun
        sub.size = 0
        for (k in 0 until run.size - 1) {
            for (step in 0 until SPIN_SUBDIVISIONS) {
                val t = step.toFloat() / SPIN_SUBDIVISIONS
                sub.add(
                    lerp(run.xs[k], run.xs[k + 1], t),
                    lerp(run.ys[k], run.ys[k + 1], t),
                    lerp(run.widths[k], run.widths[k + 1], t),
                    lerp(run.positions[k], run.positions[k + 1], t),
                )
            }
        }
        val last = run.size - 1
        sub.add(run.xs[last], run.ys[last], run.widths[last], run.positions[last])
        sub.computeNormals()
        for (k in 0 until sub.size) {
            val seconds = flightTime * sub.positions[k] / segments
            val angle = 2 * PI * twistTurns(spinRpm, seconds)
            val facing = cos(angle).toFloat()
            sub.widthFactors[k] = SPIN_MIN_WIDTH + (1 - SPIN_MIN_WIDTH) * abs(facing)
            bands[k] = if (facing >= 0f) 1 else 0
        }
        writeBands(0, sub, SPIN_WIDTH)
    }

    /** [DOTTED_EVERY]-th samples, so the beads are evenly spaced in time. */
    private fun writeBeads(
        geometry: FlightGeometry,
        at: Float,
    ) {
        val layer = layers[STYLE_START + 1]
        var index = 0
        while (index <= at && index <= geometry.segments) {
            if (geometry.isVisible(index)) {
                val radius = max(geometry.tracerWidths[index] * BEAD_RADIUS_FACTOR, MIN_BEAD_RADIUS_PIXELS)
                writeCircle(layer.path, geometry.xs[index], geometry.ys[index], radius)
                layer.visible = true
            }
            index += DOTTED_EVERY
        }
    }

    /** The ball's shadow path on the ground, up to the tip, as a thin ribbon under the tracer. */
    private fun writeGroundTrack(
        geometry: FlightGeometry,
        at: Float,
    ) {
        val layer = layers[STYLE_START]
        val run = groundRun
        run.size = 0
        val whole = at.toInt().coerceIn(0, geometry.segments)
        for (index in 0..whole) {
            addGroundPoint(geometry, index.toFloat())
        }
        if (at > whole) addGroundPoint(geometry, at)
        flushGround(layer)
    }

    private fun addGroundPoint(
        geometry: FlightGeometry,
        position: Float,
    ) {
        val x = geometry.valueAt(geometry.shadowXs, position)
        val y = geometry.valueAt(geometry.shadowYs, position)
        if (x.isNaN() || y.isNaN()) {
            flushGround(layers[STYLE_START])
            return
        }
        val width = max(geometry.valueAt(geometry.tracerWidths, position) * GROUND_TRACK_WIDTH, MIN_GROUND_TRACK_PIXELS)
        groundRun.add(x, y, width, position)
    }

    private fun flushGround(layer: TrailLayer<P>) {
        val run = groundRun
        if (run.size >= 2) {
            run.computeNormals()
            run.writeRibbon(layer.path, 0, run.size - 1, 1f)
            layer.visible = true
        }
        run.size = 0
    }

    /** The whole run into style layer [layer], [widthFactor] × its widths. */
    private fun write(
        layer: Int,
        run: TracerRun,
        widthFactor: Float,
    ) {
        val target = layers[STYLE_START + layer]
        run.writeRibbon(target.path, 0, run.size - 1, widthFactor)
        target.visible = true
    }

    /**
     * The run split where its points' [bands] change, each piece into style layer [firstLayer] +
     * its band (skipping [NO_BAND]). Neighbouring pieces share their boundary point, so the trail
     * stays continuous.
     */
    private fun writeBands(
        firstLayer: Int,
        run: TracerRun,
        widthFactor: Float,
    ) {
        var start = 0
        for (k in 1..run.size) {
            if (k < run.size && bands[k] == bands[start]) continue
            val band = bands[start]
            val end = if (k < run.size) k else run.size - 1
            if (band != NO_BAND && end > start) {
                val target = layers[STYLE_START + firstLayer + band]
                run.writeRibbon(target.path, start, end, widthFactor)
                target.visible = true
            }
            start = k
        }
        for (k in 0 until run.size) run.widthFactors[k] = 1f
    }

    private fun paintPrior(run: TracerRun) {
        val layer = layers[priorWriting]
        run.writeRibbon(layer.path, 0, run.size - 1, PRIOR_WIDTH)
        layer.visible = true
    }

    private fun cometKey(position: Float): Float = 1 - (tipPosition - position) / (COMET_LENGTH * segments)

    private fun smokeAge(position: Float): Float = ((tipPosition - position) / max(tipPosition, 1f)).coerceIn(0f, 1f)

    private fun speedAt(position: Float): Float = geometry?.let { it.valueAt(it.speeds, position) } ?: 0f

    // endregion

    // region Landing effects.

    /**
     * A ground ring (or, with [innerMeters] 0, a disc) of [outerMeters] around ([x], [z]) as one
     * outline: the outer edge one way and the inner edge the other, so non-zero filling leaves the
     * hole. False (nothing written) when any of it is behind the camera.
     */
    @Suppress("LongParameterList") // A ring's centre and radii plus where to write it.
    private fun writeAnnulus(
        sink: PathSink,
        projection: RangeProjection,
        x: Double,
        z: Double,
        outerMeters: Double,
        innerMeters: Double,
    ): Boolean {
        if (!ringVisible(projection, x, z, outerMeters)) return false
        writeGroundCircle(sink, projection, x, z, outerMeters, clockwise = false)
        if (innerMeters > 0) writeGroundCircle(sink, projection, x, z, innerMeters, clockwise = true)
        return true
    }

    private fun ringVisible(
        projection: RangeProjection,
        x: Double,
        z: Double,
        radius: Double,
    ): Boolean {
        for (step in 0 until RING_VERTICES) {
            val angle = 2 * PI * step / RING_VERTICES
            if (projection.depth(x + cos(angle) * radius, EFFECT_HEIGHT_METERS, z + sin(angle) * radius) <=
                RangeProjection.NEAR_PLANE_METERS
            ) {
                return false
            }
        }
        return true
    }

    @Suppress("LongParameterList") // A circle's centre, radius and winding plus where to write it.
    private fun writeGroundCircle(
        sink: PathSink,
        projection: RangeProjection,
        x: Double,
        z: Double,
        radius: Double,
        clockwise: Boolean,
    ) {
        for (step in 0 until RING_VERTICES) {
            val turn = if (clockwise) RING_VERTICES - step else step
            val angle = 2 * PI * turn / RING_VERTICES
            projection.projectInto(x + cos(angle) * radius, EFFECT_HEIGHT_METERS, z + sin(angle) * radius, point, 0)
            if (step == 0) sink.moveTo(point[0], point[1]) else sink.lineTo(point[0], point[1])
        }
        sink.close()
    }

    /** [SPARKLES] diamonds thrown out on arcs from the landing, landing around it at phase 1. */
    @Suppress("LongParameterList") // The landing, the phase and where to write it.
    private fun writeSparkles(
        sink: PathSink,
        projection: RangeProjection,
        x: Double,
        z: Double,
        phase: Float,
        eased: Float,
    ): Boolean {
        var any = false
        for (index in 0 until SPARKLES) {
            val angle = 2 * PI * index / SPARKLES + SPARKLE_JITTER * sin(index * SPARKLE_JITTER_STEP)
            val reach = SPARKLE_REACH_METERS * (1 + SPARKLE_REACH_SPREAD * fraction(index * GOLDEN_RATIO))
            val height = SPARKLE_HEIGHT_METERS * (1 + fraction(index * SPARKLE_HEIGHT_STEP))
            val distance = reach * eased
            val lift = height * ARC * phase * (1 - phase) + EFFECT_HEIGHT_METERS
            val px = x + cos(angle) * distance
            val pz = z + sin(angle) * distance
            val depth = projection.depth(px, lift, pz)
            if (depth <= RangeProjection.NEAR_PLANE_METERS || !projection.projectInto(px, lift, pz, point, 0)) continue
            val size =
                max(projection.pixels(SPARKLE_SIZE_METERS * (1 - SPARKLE_SHRINK * phase), depth), MIN_SPARKLE_PIXELS)
            sink.moveTo(point[0], point[1] - size)
            sink.lineTo(point[0] + size * SPARKLE_WAIST, point[1])
            sink.lineTo(point[0], point[1] + size)
            sink.lineTo(point[0] - size * SPARKLE_WAIST, point[1])
            sink.close()
            any = true
        }
        return any
    }

    // endregion

    companion object {
        /** Earlier trails kept at most ("Keep last shots"). */
        const val PRIOR_LAYERS = 3

        /** Each earlier trail's alpha multiplier, newest first. */
        val PRIOR_FADES: List<Float> = listOf(0.55f, 0.34f, 0.18f)

        const val EFFECT_LAYERS = 2
        const val STYLE_LAYERS = 12
        const val EFFECT_START = PRIOR_LAYERS
        const val STYLE_START = PRIOR_LAYERS + EFFECT_LAYERS
        const val LAYER_COUNT = STYLE_START + STYLE_LAYERS

        /** Beads every this many samples ([FlightGeometry]'s samples are evenly spaced in time). */
        const val DOTTED_EVERY = 3

        /** [ShotTrailStyle.SPIN_RIBBON] turns once per second of flight for this many rpm. */
        const val RPM_PER_TWIST_PER_SECOND = 2400.0

        /** The landing effect plays over the follow camera's settle, so it ends as the frames stop. */
        const val LANDING_EFFECT_SECONDS = 0.9f

        /** Under reduced motion the landing effect is drawn frozen at this phase. */
        const val STATIC_EFFECT_PHASE = 0.6f

        const val HEAT_BANDS = 10
        const val RAINBOW_BANDS = 12
        const val COMET_BANDS = 8
        const val SMOKE_BANDS = 8

        /** Slowest (coolest) and fastest (hottest) ball speeds of the heat scale, m/s. */
        const val HEAT_MIN_SPEED = 20f
        const val HEAT_MAX_SPEED = 75f

        /** Hues of the heat scale: cool blue at [HEAT_MIN_SPEED] to red at [HEAT_MAX_SPEED]. */
        const val HEAT_COOL_HUE = 225f
        const val HEAT_HOT_HUE = 0f
        const val RAINBOW_HUE_SPAN = 280f

        /** Spin used when a flight has none: a mid-iron's. */
        const val DEFAULT_SPIN_RPM = 5000.0

        /** How many turns the spin ribbon makes over [seconds] of flight at [spinRpm]. */
        fun twistTurns(
            spinRpm: Double,
            seconds: Double,
        ): Double = spinRpm / RPM_PER_TWIST_PER_SECOND * seconds

        /** 0 at [HEAT_MIN_SPEED] (and slower) to 1 at [HEAT_MAX_SPEED] (and faster). */
        fun heatFraction(speedMetersPerSecond: Float): Float =
            ((speedMetersPerSecond - HEAT_MIN_SPEED) / (HEAT_MAX_SPEED - HEAT_MIN_SPEED)).coerceIn(0f, 1f)

        /** The heat colour of [speedMetersPerSecond]: its hue falls from cool blue to red as it gets faster. */
        fun heatColor(speedMetersPerSecond: Float): RangeColor = heatColorAt(heatFraction(speedMetersPerSecond))

        internal fun heatColorAt(fraction: Float): RangeColor =
            hsv(HEAT_COOL_HUE + (HEAT_HOT_HUE - HEAT_COOL_HUE) * fraction, HEAT_SATURATION, 1f)

        internal fun heatBand(speedMetersPerSecond: Float): Int = bandOf(heatFraction(speedMetersPerSecond), HEAT_BANDS)

        /** [value] in 0..1 as one of [count] bands. */
        internal fun bandOf(
            value: Float,
            count: Int,
        ): Int = floor(value.coerceIn(0f, 1f) * count).toInt().coerceAtMost(count - 1)

        /** A colour from hue (degrees), saturation and value, each 0..1 but the hue. */
        @Suppress("MagicNumber") // The six hue sectors.
        internal fun hsv(
            hueDegrees: Float,
            saturation: Float,
            value: Float,
        ): RangeColor {
            val hue = ((hueDegrees % 360f) + 360f) % 360f / 60f
            val chroma = value * saturation
            val x = chroma * (1 - abs(hue % 2 - 1))
            val m = value - chroma
            val (r, g, b) =
                when (hue.toInt()) {
                    0 -> Triple(chroma, x, 0f)
                    1 -> Triple(x, chroma, 0f)
                    2 -> Triple(0f, chroma, x)
                    3 -> Triple(0f, x, chroma)
                    4 -> Triple(x, 0f, chroma)
                    else -> Triple(chroma, 0f, x)
                }
            return RangeColor(r + m, g + m, b + m)
        }

        internal const val NO_BAND = -1
        private const val HEAT_SATURATION = 0.88f
        private const val RUN_EXTRA_POINTS = 3
        private const val SPIN_SUBDIVISIONS = 4
        private const val ALPHA_SHIFT = 24
        private const val CHANNEL_MAX = 255f
        private const val ROUNDING = 0.5f
        private const val RGB_MASK = 0x00FFFFFF

        private const val PRIOR_WIDTH = 0.8f
        private const val BROADCAST_OUTER_WIDTH = 5.5f
        private const val BROADCAST_INNER_WIDTH = 2.4f
        private const val BROADCAST_CORE_WIDTH = 0.7f
        private const val CLUB_GLOW_WIDTH = 2.6f
        private const val NEON_HALO_WIDTH = 1.9f
        private const val NEON_TUBE_WIDTH = 0.6f
        private const val NEON_CORE_WIDTH = 0.22f
        private const val DOTTED_THREAD_WIDTH = 0.45f
        private const val BEAD_RADIUS_FACTOR = 1.1f
        private const val MIN_BEAD_RADIUS_PIXELS = 3f
        private const val BANDED_WIDTH = 1.1f
        private const val COMET_LENGTH = 0.45f
        private const val COMET_HEAD = 0.8f
        private const val COMET_TAIL_WIDTH = 0.3f
        private const val SMOKE_WIDTH = 1.1f
        private const val SMOKE_SPREAD = 5f
        private const val SMOKE_CORE_AGE = 0.3f
        private const val SMOKE_CORE_WIDTH = 0.45f
        private const val SPIN_WIDTH = 2.2f
        private const val SPIN_MIN_WIDTH = 0.1f
        private const val GROUND_TRACK_WIDTH = 0.5f
        private const val MIN_GROUND_TRACK_PIXELS = 1.5f

        private const val RING_VERTICES = 40
        private const val EFFECT_HEIGHT_METERS = 0.03
        private const val RING_START_METERS = 2.2
        private const val RING_END_METERS = 7.5
        private const val RING_INNER_END_METERS = 5.0
        private const val RING_WIDTH_METERS = 0.45
        private const val RING_ALPHA = 0.85f
        private const val RING_INNER_ALPHA = 0.7f
        private const val RING_FADE = 0.55f
        private const val RING_LAG = 0.25f
        private const val DUST_START_METERS = 1.0
        private const val DUST_END_METERS = 4.5
        private const val DUST_ALPHA = 0.4f
        private const val DUST_FADE = 0.6f
        private const val SPARKLES = 12
        private const val SPARKLE_ALPHA = 0.95f
        private const val SPARKLE_FADE = 0.4f
        private const val SPARKLE_JITTER = 0.35
        private const val SPARKLE_JITTER_STEP = 2.3
        private const val SPARKLE_REACH_METERS = 3.2
        private const val SPARKLE_REACH_SPREAD = 0.5
        private const val SPARKLE_HEIGHT_METERS = 1.2
        private const val SPARKLE_HEIGHT_STEP = 0.37
        private const val GOLDEN_RATIO = 0.618
        private const val ARC = 4.0
        private const val SPARKLE_SIZE_METERS = 0.35
        private const val SPARKLE_SHRINK = 0.5f
        private const val SPARKLE_WAIST = 0.45f
        private const val MIN_SPARKLE_PIXELS = 2.5f

        private fun lerp(
            a: Float,
            b: Float,
            t: Float,
        ): Float = a + (b - a) * t

        private fun easeOut(t: Float): Float = 1 - (1 - t) * (1 - t)

        private fun fraction(value: Double): Double = value - floor(value)

        /** Writes a [CIRCLE_VERTICES]-gon of [radius] around ([x], [y]). */
        private fun writeCircle(
            sink: PathSink,
            x: Float,
            y: Float,
            radius: Float,
        ) {
            for (step in 0 until CIRCLE_VERTICES) {
                val angle = 2 * PI * step / CIRCLE_VERTICES
                val px = x + radius * cos(angle).toFloat()
                val py = y + radius * sin(angle).toFloat()
                if (step == 0) sink.moveTo(px, py) else sink.lineTo(px, py)
            }
            sink.close()
        }

        private const val CIRCLE_VERTICES = 12

        /** [argb] with its alpha replaced by [alpha] (0..1). */
        internal fun withAlpha(
            argb: Int,
            alpha: Float,
        ): Int {
            val channel = (alpha.coerceIn(0f, 1f) * CHANNEL_MAX + ROUNDING).toInt()
            return (argb and RGB_MASK) or (channel shl ALPHA_SHIFT)
        }
    }
}

/**
 * The packed colours of every [ShotTrailStyle] for one [RangeVisualStyle] (plan F8a2t), worked out
 * once per theme. Most styles take the theme's tracer colours, so they stay readable on every theme;
 * NEON, SPEED_HEAT and RAINBOW are fixed saturated scales, SMOKE a pale smoke, and GROUND_TRACK a
 * shadow line (light under a night sky).
 */
@Suppress("MagicNumber") // A palette is its numbers.
internal class ShotTrailPalette(
    style: RangeVisualStyle,
) {
    val glowWidthFactor: Float = style.tracerGlowWidthFactor
    private val tracer = style.tracer
    private val glow = style.tracerGlow
    private val nightSky = luminance(style.sky.first().color) < NIGHT_SKY_LUMINANCE

    private val byStyle: Map<ShotTrailStyle, IntArray> =
        mapOf(
            ShotTrailStyle.CLASSIC to argbs(glow, tracer),
            ShotTrailStyle.BROADCAST_GLOW to
                argbs(glow.withAlpha(0.14f), glow.withAlpha(0.34f), tracer.mix(RangeColor.WHITE, 0.45f).withAlpha(1f)),
            ShotTrailStyle.COMET to
                IntArray(1 + ShotTrail.COMET_BANDS) { band ->
                    if (band == 0) {
                        glow.withAlpha(min(glow.alpha * 1.6f, 0.5f)).toArgb()
                    } else {
                        tracer.withAlpha(tracer.alpha * (band.toFloat() / ShotTrail.COMET_BANDS).pow(1.4f)).toArgb()
                    }
                },
            // The club palette colour at these alphas.
            ShotTrailStyle.CLUB_COLOUR to argbs(RangeColor.WHITE.withAlpha(0.28f), RangeColor.WHITE.withAlpha(0.92f)),
            ShotTrailStyle.DOTTED to argbs(tracer.withAlpha(0.3f), tracer.mix(RangeColor.WHITE, 0.2f).withAlpha(1f)),
            ShotTrailStyle.SMOKE to
                IntArray(ShotTrail.SMOKE_BANDS + 1) { band ->
                    if (band == ShotTrail.SMOKE_BANDS) {
                        tracer.toArgb()
                    } else {
                        val age = (band + 0.5f) / ShotTrail.SMOKE_BANDS
                        SMOKE.withAlpha(0.62f * (1 - age).pow(1.2f) + 0.06f).toArgb()
                    }
                },
            ShotTrailStyle.NEON to argbs(NEON.withAlpha(0.32f), NEON, RangeColor.WHITE.withAlpha(0.85f)),
            ShotTrailStyle.SPEED_HEAT to
                IntArray(ShotTrail.HEAT_BANDS) { band ->
                    ShotTrail.heatColorAt((band + 0.5f) / ShotTrail.HEAT_BANDS).withAlpha(0.95f).toArgb()
                },
            ShotTrailStyle.RAINBOW to
                IntArray(ShotTrail.RAINBOW_BANDS) { band ->
                    val hue = (band + 0.5f) / ShotTrail.RAINBOW_BANDS * ShotTrail.RAINBOW_HUE_SPAN
                    ShotTrail.hsv(hue, 0.85f, 1f).withAlpha(0.92f).toArgb()
                },
            ShotTrailStyle.SPIN_RIBBON to
                argbs(
                    tracer.mix(RangeColor.BLACK, 0.45f).withAlpha(0.95f),
                    tracer.mix(RangeColor.WHITE, 0.25f).withAlpha(0.95f),
                ),
            ShotTrailStyle.GROUND_TRACK to
                argbs(
                    if (nightSky) RangeColor.WHITE.withAlpha(0.3f) else RangeColor.BLACK.withAlpha(0.38f),
                    glow,
                    tracer,
                ),
        )

    /** The style's layer colours, in [ShotTrail] style-layer order. */
    fun styleLayers(style: ShotTrailStyle): IntArray = byStyle.getValue(style)

    /** An earlier trail's colour (before its fade) in [style]. */
    fun prior(style: ShotTrailStyle): Int =
        when (style) {
            ShotTrailStyle.SMOKE -> SMOKE.withAlpha(0.85f).toArgb()
            ShotTrailStyle.NEON -> NEON.withAlpha(0.85f).toArgb()
            ShotTrailStyle.SPEED_HEAT -> ShotTrail.heatColorAt(0.5f).withAlpha(0.85f).toArgb()
            ShotTrailStyle.CLUB_COLOUR -> RangeColor.WHITE.withAlpha(0.85f).toArgb()
            else -> tracer.withAlpha(0.85f).toArgb()
        }

    /** The colour at the ball end of [style]'s trail, its most prominent. */
    fun head(style: ShotTrailStyle): RangeColor =
        RangeColor.fromArgb(
            when (style) {
                ShotTrailStyle.CLASSIC, ShotTrailStyle.CLUB_COLOUR, ShotTrailStyle.DOTTED -> styleLayers(style)[1]
                ShotTrailStyle.GROUND_TRACK -> styleLayers(style)[2]
                ShotTrailStyle.BROADCAST_GLOW -> styleLayers(style)[2]
                ShotTrailStyle.NEON -> styleLayers(style)[1]
                ShotTrailStyle.COMET -> styleLayers(style)[ShotTrail.COMET_BANDS]
                ShotTrailStyle.SMOKE -> styleLayers(style)[ShotTrail.SMOKE_BANDS]
                ShotTrailStyle.SPEED_HEAT -> styleLayers(style)[ShotTrail.HEAT_BANDS - 1]
                ShotTrailStyle.RAINBOW -> styleLayers(style)[ShotTrail.RAINBOW_BANDS - 1]
                ShotTrailStyle.SPIN_RIBBON -> styleLayers(style)[1]
            }.toLong() and 0xFFFFFFFFL,
        )

    val ringArgb: Int = RangeColor.WHITE.toArgb()
    val ringInnerArgb: Int = style.landingInner.toArgb()
    val dustArgb: Int = DUST.toArgb()
    val sparkleArgb: Int = SPARKLE.toArgb()

    private fun argbs(vararg colors: RangeColor): IntArray = IntArray(colors.size) { colors[it].toArgb() }

    private fun luminance(color: RangeColor): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    companion object {
        val SMOKE = RangeColor(0.94f, 0.95f, 0.97f)
        val NEON = RangeColor(1f, 0.16f, 0.78f)
        val DUST = RangeColor(0.86f, 0.80f, 0.66f)
        val SPARKLE = RangeColor(1f, 0.9f, 0.5f)
        private const val NIGHT_SKY_LUMINANCE = 0.12f
    }
}
