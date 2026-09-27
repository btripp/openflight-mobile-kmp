// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.flight.RangeTracerStyle

/**
 * The range's look (plan F8a2a): a [RangeVisualStyle] per theme, all vector and procedural, drawn
 * identically by both renderers. [DAY] carries the atmosphere pass (haze, a banded sky with a sun
 * and a pale horizon, far ridges, mottled grass, three-tone trees and a tracer glow); [DUSK],
 * [NIGHT] and [LINKS] are the same scene with other data.
 *
 * Every theme keeps the yardage labels, markers and tracer readable against its ground and sky:
 * NIGHT's range is floodlit, its tracer a bright cyan with a strong glow.
 */
@Suppress("MagicNumber") // A palette is its numbers.
enum class RangeTheme(
    val style: RangeVisualStyle,
) {
    DAY(
        themeStyle(
            sky =
                listOf(
                    RangeGradientStop(0f, RangeColor(0.25f, 0.50f, 0.83f)),
                    RangeGradientStop(0.45f, RangeColor(0.39f, 0.64f, 0.90f)),
                    RangeGradientStop(0.80f, RangeColor(0.61f, 0.79f, 0.94f)),
                    RangeGradientStop(0.94f, RangeColor(0.79f, 0.88f, 0.95f)),
                    RangeGradientStop(1f, RangeColor(0.88f, 0.92f, 0.93f)),
                ),
            ground = RangeColor(0.12f, 0.34f, 0.15f),
            fairway = RangeColor(0.20f, 0.50f, 0.21f),
            stripe = RangeColor(0.24f, 0.56f, 0.25f),
            tee = RangeColor(0.16f, 0.47f, 0.20f),
            trunk = RangeColor(0.29f, 0.16f, 0.08f),
            crownDark = RangeColor(0.04f, 0.20f, 0.08f),
            crownMid = RangeColor(0.08f, 0.31f, 0.12f),
            crownLight = RangeColor(0.20f, 0.45f, 0.17f),
            treeShadow = RangeColor.BLACK.withAlpha(0.24f),
            mottleLight = RangeColor(0.62f, 0.82f, 0.36f, 0.10f),
            mottleDark = RangeColor(0.02f, 0.14f, 0.05f, 0.12f),
            tracer = RangeColor.of(RangeTracerStyle.highVisibility),
            tracerGlow = RangeColor(0.35f, 0.65f, 1f, 0.22f),
            haze =
                RangeHaze(
                    RangeColor(0.74f, 0.83f, 0.88f),
                    startMeters = 25.0,
                    depthMeters = 420.0,
                    maxAmount = 0.78f,
                ),
            sun =
                RangeSun(
                    azimuthDegrees = -32.0,
                    elevationDegrees = 17.0,
                    discRadiusDegrees = 1.1,
                    disc = RangeColor(1f, 0.98f, 0.90f),
                    glowRadiusDegrees = 11.0,
                    glow =
                        listOf(
                            RangeGradientStop(0f, RangeColor(1f, 1f, 0.96f, 0.55f)),
                            RangeGradientStop(0.25f, RangeColor(1f, 0.97f, 0.86f, 0.28f)),
                            RangeGradientStop(0.6f, RangeColor(1f, 0.95f, 0.85f, 0.08f)),
                            RangeGradientStop(1f, RangeColor(1f, 0.95f, 0.85f, 0f)),
                        ),
                ),
            ridges =
                listOf(
                    RangeRidge(
                        RangeColor(0.56f, 0.67f, 0.73f),
                        baseDegrees = 0.35,
                        amplitudeDegrees = 1.1,
                        seed = 11,
                        canopy = 0f,
                    ),
                    RangeRidge(
                        RangeColor(0.30f, 0.43f, 0.39f),
                        baseDegrees = 0.45,
                        amplitudeDegrees = 0.45,
                        seed = 23,
                        canopy = 1f,
                    ),
                ),
        ),
    ),

    DUSK(
        themeStyle(
            sky =
                listOf(
                    RangeGradientStop(0f, RangeColor(0.16f, 0.14f, 0.34f)),
                    RangeGradientStop(0.45f, RangeColor(0.38f, 0.26f, 0.46f)),
                    RangeGradientStop(0.75f, RangeColor(0.78f, 0.44f, 0.40f)),
                    RangeGradientStop(0.92f, RangeColor(0.97f, 0.66f, 0.38f)),
                    RangeGradientStop(1f, RangeColor(1f, 0.80f, 0.52f)),
                ),
            ground = RangeColor(0.09f, 0.22f, 0.12f),
            fairway = RangeColor(0.19f, 0.38f, 0.17f),
            stripe = RangeColor(0.23f, 0.43f, 0.20f),
            tee = RangeColor(0.16f, 0.36f, 0.16f),
            trunk = RangeColor(0.20f, 0.11f, 0.07f),
            crownDark = RangeColor(0.04f, 0.10f, 0.07f),
            crownMid = RangeColor(0.08f, 0.18f, 0.10f),
            crownLight = RangeColor(0.35f, 0.30f, 0.14f),
            treeShadow = RangeColor.BLACK.withAlpha(0.30f),
            mottleLight = RangeColor(0.90f, 0.60f, 0.30f, 0.08f),
            mottleDark = RangeColor(0.05f, 0.05f, 0.10f, 0.14f),
            // Cyan: it stands off both the purple zenith and the orange horizon.
            tracer = RangeColor(0.30f, 0.90f, 1f, 0.92f),
            tracerGlow = RangeColor(0.45f, 0.85f, 1f, 0.24f),
            haze =
                RangeHaze(
                    RangeColor(0.80f, 0.55f, 0.50f),
                    startMeters = 20.0,
                    depthMeters = 380.0,
                    maxAmount = 0.75f,
                ),
            sun =
                RangeSun(
                    azimuthDegrees = -18.0,
                    elevationDegrees = 3.2,
                    discRadiusDegrees = 1.5,
                    disc = RangeColor(1f, 0.78f, 0.42f),
                    glowRadiusDegrees = 16.0,
                    glow =
                        listOf(
                            RangeGradientStop(0f, RangeColor(1f, 0.75f, 0.40f, 0.65f)),
                            RangeGradientStop(0.3f, RangeColor(1f, 0.60f, 0.30f, 0.30f)),
                            RangeGradientStop(0.65f, RangeColor(0.95f, 0.50f, 0.30f, 0.10f)),
                            RangeGradientStop(1f, RangeColor(0.95f, 0.50f, 0.30f, 0f)),
                        ),
                ),
            ridges =
                listOf(
                    RangeRidge(
                        RangeColor(0.45f, 0.33f, 0.45f),
                        baseDegrees = 0.35,
                        amplitudeDegrees = 1.2,
                        seed = 11,
                        canopy = 0f,
                    ),
                    RangeRidge(
                        RangeColor(0.20f, 0.16f, 0.24f),
                        baseDegrees = 0.45,
                        amplitudeDegrees = 0.5,
                        seed = 23,
                        canopy = 1f,
                    ),
                ),
        ),
    ),

    NIGHT(
        themeStyle(
            sky =
                listOf(
                    RangeGradientStop(0f, RangeColor(0.02f, 0.03f, 0.08f)),
                    RangeGradientStop(0.5f, RangeColor(0.04f, 0.06f, 0.14f)),
                    RangeGradientStop(0.85f, RangeColor(0.08f, 0.11f, 0.22f)),
                    RangeGradientStop(1f, RangeColor(0.14f, 0.17f, 0.28f)),
                ),
            // A floodlit range: the grass stays green near the bays and fades into the dark.
            ground = RangeColor(0.06f, 0.20f, 0.10f),
            fairway = RangeColor(0.14f, 0.40f, 0.18f),
            stripe = RangeColor(0.18f, 0.46f, 0.22f),
            tee = RangeColor(0.12f, 0.36f, 0.16f),
            trunk = RangeColor(0.10f, 0.07f, 0.05f),
            crownDark = RangeColor(0.01f, 0.05f, 0.03f),
            crownMid = RangeColor(0.03f, 0.11f, 0.06f),
            crownLight = RangeColor(0.08f, 0.20f, 0.10f),
            treeShadow = RangeColor.BLACK.withAlpha(0.35f),
            mottleLight = RangeColor(0.40f, 0.70f, 0.40f, 0.06f),
            mottleDark = RangeColor(0f, 0f, 0f, 0.14f),
            tracer = RangeColor(0.45f, 0.90f, 1f, 0.95f),
            tracerGlow = RangeColor(0.35f, 0.80f, 1f, 0.30f),
            tracerGlowWidthFactor = 3.0f,
            haze =
                RangeHaze(
                    RangeColor(0.06f, 0.08f, 0.14f),
                    startMeters = 40.0,
                    depthMeters = 260.0,
                    maxAmount = 0.85f,
                ),
            sun =
                RangeSun(
                    azimuthDegrees = 28.0,
                    elevationDegrees = 24.0,
                    discRadiusDegrees = 0.9,
                    disc = RangeColor(0.93f, 0.95f, 1f),
                    glowRadiusDegrees = 7.0,
                    glow =
                        listOf(
                            RangeGradientStop(0f, RangeColor(0.80f, 0.85f, 1f, 0.30f)),
                            RangeGradientStop(0.4f, RangeColor(0.60f, 0.70f, 1f, 0.10f)),
                            RangeGradientStop(1f, RangeColor(0.60f, 0.70f, 1f, 0f)),
                        ),
                ),
            ridges =
                listOf(
                    RangeRidge(
                        RangeColor(0.07f, 0.09f, 0.15f),
                        baseDegrees = 0.35,
                        amplitudeDegrees = 1.1,
                        seed = 11,
                        canopy = 0f,
                    ),
                    RangeRidge(
                        RangeColor(0.03f, 0.05f, 0.07f),
                        baseDegrees = 0.45,
                        amplitudeDegrees = 0.45,
                        seed = 23,
                        canopy = 1f,
                    ),
                ),
        ),
    ),

    LINKS(
        themeStyle(
            sky =
                listOf(
                    RangeGradientStop(0f, RangeColor(0.50f, 0.62f, 0.74f)),
                    RangeGradientStop(0.5f, RangeColor(0.66f, 0.75f, 0.83f)),
                    RangeGradientStop(0.85f, RangeColor(0.80f, 0.85f, 0.88f)),
                    RangeGradientStop(1f, RangeColor(0.88f, 0.89f, 0.88f)),
                ),
            // Golden fescue rough, a firm fairway, and gorse for trees.
            ground = RangeColor(0.52f, 0.50f, 0.28f),
            fairway = RangeColor(0.36f, 0.52f, 0.24f),
            stripe = RangeColor(0.40f, 0.56f, 0.27f),
            tee = RangeColor(0.30f, 0.46f, 0.20f),
            trunk = RangeColor(0.25f, 0.18f, 0.10f),
            crownDark = RangeColor(0.10f, 0.18f, 0.08f),
            crownMid = RangeColor(0.20f, 0.28f, 0.10f),
            crownLight = RangeColor(0.55f, 0.50f, 0.15f),
            treeShadow = RangeColor.BLACK.withAlpha(0.20f),
            mottleLight = RangeColor(0.85f, 0.78f, 0.45f, 0.14f),
            mottleDark = RangeColor(0.25f, 0.28f, 0.12f, 0.14f),
            tracer = RangeColor(0.90f, 0.16f, 0.14f, 0.88f),
            tracerGlow = RangeColor(1f, 0.40f, 0.35f, 0.20f),
            haze =
                RangeHaze(
                    RangeColor(0.80f, 0.83f, 0.84f),
                    startMeters = 20.0,
                    depthMeters = 480.0,
                    maxAmount = 0.70f,
                ),
            sun =
                RangeSun(
                    azimuthDegrees = 38.0,
                    elevationDegrees = 20.0,
                    discRadiusDegrees = 1.0,
                    disc = RangeColor(1f, 1f, 0.95f, 0.85f),
                    glowRadiusDegrees = 14.0,
                    glow =
                        listOf(
                            RangeGradientStop(0f, RangeColor(1f, 1f, 0.95f, 0.35f)),
                            RangeGradientStop(0.5f, RangeColor(1f, 1f, 0.95f, 0.08f)),
                            RangeGradientStop(1f, RangeColor(1f, 1f, 0.95f, 0f)),
                        ),
                ),
            ridges =
                listOf(
                    RangeRidge(
                        RangeColor(0.60f, 0.66f, 0.66f),
                        baseDegrees = 0.30,
                        amplitudeDegrees = 0.9,
                        seed = 31,
                        canopy = 0f,
                    ),
                    RangeRidge(
                        RangeColor(0.52f, 0.54f, 0.38f),
                        baseDegrees = 0.40,
                        amplitudeDegrees = 0.6,
                        seed = 47,
                        canopy = 0.3f,
                    ),
                ),
        ),
    ),
    ;

    companion object {
        /** The theme a persisted [RangeThemeSetting] names. */
        fun of(setting: RangeThemeSetting): RangeTheme =
            when (setting) {
                RangeThemeSetting.DAY -> DAY
                RangeThemeSetting.DUSK -> DUSK
                RangeThemeSetting.NIGHT -> NIGHT
                RangeThemeSetting.LINKS -> LINKS
            }
    }
}

/** What every theme shares: the markers, landing, ball, labels, scrim and sizes. */
@Suppress("LongParameterList", "MagicNumber") // One theme's colours.
private fun themeStyle(
    sky: List<RangeGradientStop>,
    ground: RangeColor,
    fairway: RangeColor,
    stripe: RangeColor,
    tee: RangeColor,
    trunk: RangeColor,
    crownDark: RangeColor,
    crownMid: RangeColor,
    crownLight: RangeColor,
    treeShadow: RangeColor,
    mottleLight: RangeColor,
    mottleDark: RangeColor,
    tracer: RangeColor,
    tracerGlow: RangeColor,
    haze: RangeHaze,
    sun: RangeSun,
    ridges: List<RangeRidge>,
    tracerGlowWidthFactor: Float = 2.6f,
): RangeVisualStyle =
    RangeVisualStyle(
        sky = sky,
        ground = ground,
        fairway = fairway,
        stripe = stripe,
        targetLine = RangeColor.WHITE.withAlpha(0.35f),
        tee = tee,
        teeMarker = RangeColor.WHITE,
        markerOuter = RangeColor.WHITE.withAlpha(0.88f),
        markerRed = RangeColor(0.90f, 0.18f, 0.15f),
        markerYellow = RangeColor(0.96f, 0.72f, 0.08f),
        trunk = trunk,
        crownDark = crownDark,
        crownMid = crownMid,
        crownLight = crownLight,
        treeShadow = treeShadow,
        mottleLight = mottleLight,
        mottleDark = mottleDark,
        landingOuter = RangeColor.WHITE.withAlpha(0.85f),
        landingInner = RangeColor(1f, 0.72f, 0.06f, 0.95f),
        tracer = tracer,
        tracerGlow = tracerGlow,
        tracerGlowWidthFactor = tracerGlowWidthFactor,
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
        haze = haze,
        sun = sun,
        ridges = ridges,
    )
