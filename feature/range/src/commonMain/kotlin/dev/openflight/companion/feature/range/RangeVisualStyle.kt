// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeTracerStyle

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

/** One stop of a vertical gradient: [color] at [offset] (0 = top, 1 = bottom). */
data class RangeGradientStop(
    val offset: Float,
    val color: RangeColor,
)

/**
 * Everything a range renderer needs to look the same on both platforms (plan F8c1): the palette
 * and the on-screen sizes. Scene *geometry* (where things are) lives in [RangeScene]; this is only
 * how they're painted.
 *
 * Sizes are in density-independent units (Android dp/sp, iOS points) unless the name says pixels
 * or metres.
 *
 * @property skyTop the sky gradient's colour at the top of the canvas.
 * @property skyHorizon the sky gradient's colour at the horizon (the gradient spans top → horizon).
 * @property ground the ground plane, and the distant ground band from the horizon to the canvas
 *   bottom (the ground plane is finite).
 * @property teeMarker the two tee-box marker discs.
 * @property tracer the live tracer ribbon ([RangeTracerStyle.highVisibility]).
 * @property ball the ball on the tracer's tip.
 * @property shade the scrim over the whole scene that keeps the overlaid controls readable.
 * @property overlayTracerAlpha the alpha of the overlay's club-coloured trajectories (the club
 *   palette itself is the design system's, `OfClubPalette` / `Theme.clubColors`).
 * @property rollOutDashPixels the roll-out segment's dash, in pixels (not dp).
 * @property labelHeightMeters a yardage label's cap height in the world, before clamping to
 *   [minLabelSize]..[maxLabelSize].
 * @property ballRadiusMeters the ball's radius in the scene ([RangeProjection.BALL_RADIUS_METERS]).
 */
data class RangeVisualStyle(
    val skyTop: RangeColor,
    val skyHorizon: RangeColor,
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
    val crownLight: RangeColor,
    val landingOuter: RangeColor,
    val landingInner: RangeColor,
    val tracer: RangeColor,
    val ball: RangeColor,
    val shadow: RangeColor,
    val label: RangeColor,
    val overlaySelected: RangeColor,
    val rollOut: RangeColor,
    val shade: List<RangeGradientStop>,
    val overlayTracerAlpha: Float,
    val labelHeightMeters: Float,
    val minLabelSize: Float,
    val maxLabelSize: Float,
    val rollOutLabelSize: Float,
    val overlayStrokeWidth: Float,
    val selectedStrokeWidth: Float,
    val overlayDotRadius: Float,
    val rollOutStrokeWidth: Float,
    val rollOutDotRadius: Float,
    val rollOutDashPixels: Float,
    val rollOutGapPixels: Float,
    val ballRadiusMeters: Double = RangeProjection.BALL_RADIUS_METERS,
)

/**
 * The range's look. [DAY] is the original palette (the reference's RangeSceneController.swift
 * colours, as Android has drawn them since R7); plan F8a2 adds DUSK, NIGHT and LINKS as data.
 */
@Suppress("MagicNumber") // A palette is its numbers.
enum class RangeTheme(
    val style: RangeVisualStyle,
) {
    DAY(
        RangeVisualStyle(
            skyTop = RangeColor(0.34f, 0.63f, 0.88f),
            skyHorizon = RangeColor(0.68f, 0.84f, 0.95f),
            ground = RangeColor(0.08f, 0.30f, 0.14f),
            fairway = RangeColor(0.20f, 0.52f, 0.22f),
            stripe = RangeColor(0.25f, 0.59f, 0.27f),
            targetLine = RangeColor.WHITE.withAlpha(0.35f),
            tee = RangeColor(0.16f, 0.47f, 0.20f),
            teeMarker = RangeColor.WHITE,
            markerOuter = RangeColor.WHITE.withAlpha(0.88f),
            markerRed = RangeColor(0.90f, 0.18f, 0.15f),
            markerYellow = RangeColor(0.96f, 0.72f, 0.08f),
            trunk = RangeColor(0.29f, 0.16f, 0.08f),
            crownDark = RangeColor(0.05f, 0.26f, 0.10f),
            crownLight = RangeColor(0.07f, 0.34f, 0.13f),
            landingOuter = RangeColor.WHITE.withAlpha(0.85f),
            landingInner = RangeColor(1f, 0.72f, 0.06f, 0.95f),
            tracer = RangeColor.of(RangeTracerStyle.highVisibility),
            ball = RangeColor.WHITE,
            shadow = RangeColor.BLACK.withAlpha(0.28f),
            label = RangeColor.WHITE,
            overlaySelected = RangeColor.fromArgb(0xFFD4AF37),
            rollOut = RangeColor.WHITE.withAlpha(0.9f),
            shade =
                listOf(
                    RangeGradientStop(0f, RangeColor.BLACK.withAlpha(0.38f)),
                    RangeGradientStop(0.5f, RangeColor.TRANSPARENT),
                    RangeGradientStop(1f, RangeColor.BLACK.withAlpha(0.60f)),
                ),
            overlayTracerAlpha = 0.7f,
            labelHeightMeters = 2.2f,
            minLabelSize = 9f,
            maxLabelSize = 16f,
            rollOutLabelSize = 13f,
            overlayStrokeWidth = 2f,
            selectedStrokeWidth = 4f,
            overlayDotRadius = 3f,
            rollOutStrokeWidth = 2.5f,
            rollOutDotRadius = 5f,
            rollOutDashPixels = 10f,
            rollOutGapPixels = 8f,
        ),
    ),
}
