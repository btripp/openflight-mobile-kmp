// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeTracerStyle
import kotlin.math.exp
import kotlin.math.ln

/**
 * A colour in sRGB, each component 0..1, with straight (not premultiplied) [alpha]. Pure data, so
 * both renderers read the same palette: Android maps it to a Compose `Color`, iOS (plan F8c2) to a
 * `Color`/`CGColor`.
 */
data class RangeColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float = 1f,
) {
    /** This colour with [alpha] replaced. */
    fun withAlpha(alpha: Float): RangeColor = copy(alpha = alpha)

    /**
     * This colour moved [amount] (0..1) of the way to [target]'s red, green and blue, keeping this
     * colour's alpha. Exactly this colour at 0.
     */
    fun mix(
        target: RangeColor,
        amount: Float,
    ): RangeColor {
        if (amount <= 0f) return this
        val t = amount.coerceAtMost(1f)
        return RangeColor(
            red = red + (target.red - red) * t,
            green = green + (target.green - green) * t,
            blue = blue + (target.blue - blue) * t,
            alpha = alpha,
        )
    }

    /** This colour packed as `0xAARRGGBB`, each channel rounded to 8 bits. */
    fun toArgb(): Int = packArgb(red, green, blue, alpha)

    /** Every channel but alpha scaled by [factor] and clamped to 0..1 (a tree's brightness jitter). */
    fun scaled(factor: Float): RangeColor =
        RangeColor(
            red = (red * factor).coerceIn(0f, 1f),
            green = (green * factor).coerceIn(0f, 1f),
            blue = (blue * factor).coerceIn(0f, 1f),
            alpha = alpha,
        )

    companion object {
        val WHITE = RangeColor(1f, 1f, 1f)
        val BLACK = RangeColor(0f, 0f, 0f)
        val TRANSPARENT = RangeColor(0f, 0f, 0f, 0f)

        /** A packed `0xAARRGGBB` colour. */
        @Suppress("MagicNumber") // Channel shifts and the 8-bit channel range.
        fun fromArgb(argb: Long): RangeColor =
            RangeColor(
                red = ((argb shr 16) and 0xFF) / 255f,
                green = ((argb shr 8) and 0xFF) / 255f,
                blue = (argb and 0xFF) / 255f,
                alpha = ((argb shr 24) and 0xFF) / 255f,
            )

        /** The tracer colour of [style]. */
        fun of(style: RangeTracerStyle): RangeColor =
            RangeColor(
                red = style.red.toFloat(),
                green = style.green.toFloat(),
                blue = style.blue.toFloat(),
                alpha = style.opacity.toFloat(),
            )
    }
}

/**
 * One stop of a gradient: [color] at [offset]. Vertical gradients run 0 = top to 1 = bottom; the
 * sun's glow runs 0 = centre to 1 = its edge.
 */
data class RangeGradientStop(
    val offset: Float,
    val color: RangeColor,
)

/**
 * Aerial perspective (plan F8a2a): the farther a shape is from the camera, the more of [color] it
 * takes on, so the range fades into the horizon instead of ending at a hard dark band.
 *
 * Plan F8a2p: the distance is [RangeProjection.hazeDistance], the depth along the camera's heading
 * (depth fog), measured from the camera, not the tee, so the follow camera flying down the range
 * sees clear grass under it and haze ahead. Everything on the ground is hazed at once by one
 * gradient overlay ([overlayStops]), continuous from the camera to the horizon; the markers and
 * trees, drawn over it, each take one colour per pose from their own distance ([apply]).
 *
 * @property startMeters no haze nearer than this.
 * @property depthMeters the e-folding distance of the fade past [startMeters].
 * @property maxAmount the haze at infinity (the backdrop's distant ground, [farColor]).
 */
data class RangeHaze(
    val color: RangeColor,
    val startMeters: Double,
    val depthMeters: Double,
    val maxAmount: Float,
) {
    /** How much haze a shape [distanceMeters] down the range takes on: 0 up to [startMeters], rising to [maxAmount]. */
    fun amount(distanceMeters: Double): Float =
        if (distanceMeters <= startMeters) {
            0f
        } else {
            (maxAmount * (1.0 - exp(-(distanceMeters - startMeters) / depthMeters))).toFloat()
        }

    /** [base] seen from [distanceMeters] down the range; [strength] (0..1) weakens it for markers. */
    fun apply(
        base: RangeColor,
        distanceMeters: Double,
        strength: Float = 1f,
    ): RangeColor = base.mix(color, amount(distanceMeters) * strength)

    /** [base] at infinity. */
    fun farColor(base: RangeColor): RangeColor = base.mix(color, maxAmount)

    /**
     * Plan F8a2p: the distance the ground haze gradient is laid out against: offset 1 of
     * [overlayStops] is the ground this far away (no haze yet), offset 0 is the horizon.
     */
    val referenceMeters: Double get() = startMeters.coerceAtLeast(MIN_REFERENCE_METERS)

    /**
     * Plan F8a2p: the ground's aerial perspective as one vertical gradient of [color] whose alpha
     * is the haze [amount], laid over everything drawn on the ground. Its offset `u` is
     * [referenceMeters] / distance, which is linear in screen y from the horizon (u = 0, alpha
     * [maxAmount]) down to the ground [referenceMeters] away (u = 1, alpha 0; clamped nearer), so
     * the renderers only move and stretch it per pose ([RangeScene.hazeTopY],
     * [RangeScene.hazeBottomY]). The stops are spaced so the alpha steps evenly: a stop apart
     * differs by `maxAmount / OVERLAY_STEPS`, with linear interpolation in between.
     */
    val overlayStops: List<RangeGradientStop> by lazy {
        val stops = mutableListOf(RangeGradientStop(0f, color.withAlpha(maxAmount)))
        for (step in OVERLAY_STEPS - 1 downTo 0) {
            val fraction = step.toDouble() / OVERLAY_STEPS
            val distance = startMeters - depthMeters * ln(1.0 - fraction)
            val offset = (referenceMeters / distance).coerceIn(0.0, 1.0).toFloat()
            stops += RangeGradientStop(offset, color.withAlpha(amount(distance)))
        }
        stops
    }

    private companion object {
        const val MIN_REFERENCE_METERS = 1.0

        /** How many even alpha steps [overlayStops] takes from clear to [maxAmount]. */
        const val OVERLAY_STEPS = 16
    }

    /** [apply] packed as `0xAARRGGBB`, without allocating (the per-pose re-tint). */
    fun argb(
        base: RangeColor,
        distanceMeters: Double,
        strength: Float,
    ): Int {
        val t = (amount(distanceMeters) * strength).coerceIn(0f, 1f)
        return packArgb(
            base.red + (color.red - base.red) * t,
            base.green + (color.green - base.green) * t,
            base.blue + (color.blue - base.blue) * t,
            base.alpha,
        )
    }
}

/** Channels 0..1 packed as `0xAARRGGBB`, each rounded to 8 bits. */
@Suppress("MagicNumber") // Channel shifts and the 8-bit channel range.
internal fun packArgb(
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float,
): Int {
    fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/**
 * The sun (or, at night, the moon): a disc and a soft radial glow at a fixed direction in the sky,
 * projected at infinity, so it turns with the camera's orbit and yaw but never moves as the camera
 * travels. Static: it never pulses, so it needs nothing for reduced motion.
 *
 * @property azimuthDegrees from straight down the range, positive to the right.
 * @property elevationDegrees above the horizon.
 * @property glow the glow's radial stops, 0 at the centre to 1 at [glowRadiusDegrees].
 */
data class RangeSun(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val discRadiusDegrees: Double,
    val disc: RangeColor,
    val glowRadiusDegrees: Double,
    val glow: List<RangeGradientStop>,
)

/**
 * A far silhouette on the horizon (plan F8a2a): hills, dunes or a tree line, drawn as one filled
 * outline at infinity whose height above the horizon comes from a seeded, deterministic function
 * of the direction ([RangeSky]). Every theme lists its ridges far to near, the order they're drawn.
 *
 * @property baseDegrees the lowest the silhouette gets above the horizon.
 * @property amplitudeDegrees how much higher its peaks rise.
 * @property seed picks the silhouette's shape (the same seed draws the same skyline on both platforms).
 * @property canopy 0 for smooth hills, 1 for a bumpy tree canopy.
 */
data class RangeRidge(
    val color: RangeColor,
    val baseDegrees: Double,
    val amplitudeDegrees: Double,
    val seed: Int,
    val canopy: Float,
)

/**
 * Everything a range renderer needs to look the same on both platforms (plan F8c1): the palette
 * and the on-screen sizes. Scene *geometry* (where things are) lives in [RangeScene]; this is only
 * how they're painted. Plan F8a2a added the atmosphere: the multi-band [sky], the [sun], the far
 * [ridges], the [haze], grass mottling, three-tone trees with contact shadows and the tracer glow.
 *
 * Sizes are in density-independent units (Android dp/sp, iOS points) unless the name says pixels
 * or metres.
 *
 * @property sky the sky's vertical gradient, from the top of the canvas (0) to the horizon (1): the
 *   zenith colour down to a pale horizon band.
 * @property ground the rough: one flat fill from the horizon down (plan F8a2p), under the
 *   [haze] overlay, so it reaches [distantGround] at the horizon.
 * @property fairway the fairway's base (its darker mowing band); [stripe] is the lighter band,
 *   drawn soft-edged with [stripeGradient].
 * @property teeMarker the two tee-box marker discs.
 * @property crownDark a crown's shaded underside; [crownMid] its body; [crownLight] its sunlit top.
 * @property treeShadow the soft contact shadow under each tree.
 * @property mottleLight low-alpha light patches in the grass; [mottleDark] the dark ones.
 * @property tracer the live tracer ribbon's core; [tracerGlow] the wider soft ribbon under it,
 *   [tracerGlowWidthFactor] times the core's width.
 * @property ball the ball on the tracer's tip.
 * @property shade the scrim over the whole scene that keeps the overlaid controls readable.
 * @property haze the aerial perspective: one gradient overlay over the ground ([RangeHaze.overlayStops])
 *   and a per-pose tint for the markers and trees.
 * @property sun the sun or moon.
 * @property ridges the horizon silhouettes, far to near.
 * @property overlayTracerAlpha the alpha of the overlay's club-coloured trajectories (the club
 *   palette itself is the design system's, `OfClubPalette` / `Theme.clubColors`).
 * @property rollOutDashPixels the roll-out segment's dash, in pixels (not dp).
 * @property labelHeightMeters a yardage label's cap height in the world, before clamping to
 *   [minLabelSize]..[maxLabelSize].
 * @property ballRadiusMeters the ball's radius in the scene ([RangeProjection.BALL_RADIUS_METERS]).
 */
data class RangeVisualStyle(
    val sky: List<RangeGradientStop>,
    val ground: RangeColor,
    val fairway: RangeColor,
    val stripe: RangeColor,
    val targetLine: RangeColor,
    val tee: RangeColor,
    val teeMarker: RangeColor,
    val markerOuter: RangeColor,
    val markerRed: RangeColor,
    val markerYellow: RangeColor,
    val trunk: RangeColor,
    val crownDark: RangeColor,
    val crownMid: RangeColor,
    val crownLight: RangeColor,
    val treeShadow: RangeColor,
    val mottleLight: RangeColor,
    val mottleDark: RangeColor,
    val landingOuter: RangeColor,
    val landingInner: RangeColor,
    val tracer: RangeColor,
    val tracerGlow: RangeColor,
    val tracerGlowWidthFactor: Float,
    val ball: RangeColor,
    val shadow: RangeColor,
    val label: RangeColor,
    val overlaySelected: RangeColor,
    val rollOut: RangeColor,
    val shade: List<RangeGradientStop>,
    val haze: RangeHaze,
    val sun: RangeSun,
    val ridges: List<RangeRidge>,
    val overlayTracerAlpha: Float = OVERLAY_TRACER_ALPHA,
    val labelHeightMeters: Float = LABEL_HEIGHT_METERS,
    val minLabelSize: Float = MIN_LABEL_SIZE,
    val maxLabelSize: Float = MAX_LABEL_SIZE,
    val rollOutLabelSize: Float = ROLL_OUT_LABEL_SIZE,
    val overlayStrokeWidth: Float = OVERLAY_STROKE_WIDTH,
    val selectedStrokeWidth: Float = SELECTED_STROKE_WIDTH,
    val overlayDotRadius: Float = OVERLAY_DOT_RADIUS,
    val rollOutStrokeWidth: Float = ROLL_OUT_STROKE_WIDTH,
    val rollOutDotRadius: Float = ROLL_OUT_DOT_RADIUS,
    val rollOutDashPixels: Float = ROLL_OUT_DASH_PIXELS,
    val rollOutGapPixels: Float = ROLL_OUT_GAP_PIXELS,
    val ballRadiusMeters: Double = RangeProjection.BALL_RADIUS_METERS,
) {
    /** The ground at infinity: the band from the horizon down, past the end of the ground plane. */
    val distantGround: RangeColor get() = haze.farColor(ground)

    /**
     * Plan F8a2p: one soft mowing stripe across its depth, 0 at its near edge to 1 at its far edge:
     * [stripe] fading in from nothing, full across its middle and out again, so neighbouring
     * stripes meet at the darker [fairway] with no edge. Each stripe fills its outline with this
     * gradient laid from its near edge to its far edge ([WorldStripe]); the ground haze goes over it.
     */
    val stripeGradient: List<RangeGradientStop> by lazy {
        STRIPE_PROFILE.map { (offset, alpha) -> RangeGradientStop(offset, stripe.withAlpha(stripe.alpha * alpha)) }
    }

    private companion object {
        /** (offset, alpha) along a stripe: a smooth bell, clear at both edges. */
        val STRIPE_PROFILE =
            listOf(
                0f to 0f,
                0.12f to 0.18f,
                0.25f to 0.6f,
                0.38f to 0.92f,
                0.5f to 1f,
                0.62f to 0.92f,
                0.75f to 0.6f,
                0.88f to 0.18f,
                1f to 0f,
            )

        const val OVERLAY_TRACER_ALPHA = 0.7f
        const val LABEL_HEIGHT_METERS = 2.2f
        const val MIN_LABEL_SIZE = 9f
        const val MAX_LABEL_SIZE = 16f
        const val ROLL_OUT_LABEL_SIZE = 13f
        const val OVERLAY_STROKE_WIDTH = 2f
        const val SELECTED_STROKE_WIDTH = 4f
        const val OVERLAY_DOT_RADIUS = 3f
        const val ROLL_OUT_STROKE_WIDTH = 2.5f
        const val ROLL_OUT_DOT_RADIUS = 5f
        const val ROLL_OUT_DASH_PIXELS = 10f
        const val ROLL_OUT_GAP_PIXELS = 8f
    }
}
