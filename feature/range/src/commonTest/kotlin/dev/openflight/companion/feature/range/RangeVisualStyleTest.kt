// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotEqualTo
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.flight.RangeQualityProfile
import dev.openflight.companion.core.flight.RangeTracerStyle
import kotlin.math.pow
import kotlin.test.Test

/**
 * Plan F8a2a: the range themes as data. F8c1's DAY was the original Android palette; the
 * atmosphere pass re-baselined it on purpose, so these check the shape of every theme (sky bands,
 * haze, sun, ridges, readability) and keep the shared markers, scrim and sizes pinned.
 */
class RangeVisualStyleTest {
    private val day = RangeTheme.DAY.style

    @Test
    fun everySettingMapsToItsTheme() {
        assertThat(RangeThemeSetting.entries.map { RangeTheme.of(it) })
            .containsExactly(RangeTheme.DAY, RangeTheme.DUSK, RangeTheme.NIGHT, RangeTheme.LINKS)
    }

    @Test
    fun everyThemeLooksDifferent() {
        val styles = RangeTheme.entries.map { it.style }
        for (i in styles.indices) {
            for (j in i + 1 until styles.size) {
                assertThat(styles[i].sky).isNotEqualTo(styles[j].sky)
                assertThat(styles[i].ground).isNotEqualTo(styles[j].ground)
            }
        }
    }

    @Test
    fun everySkyRunsFromTheTopToTheHorizonInOrder() {
        for (theme in RangeTheme.entries) {
            val offsets = theme.style.sky.map { it.offset }
            assertThat(offsets.first(), theme.name).isEqualTo(0f)
            assertThat(offsets.last(), theme.name).isEqualTo(1f)
            assertThat(offsets, theme.name).isEqualTo(offsets.sorted())
            assertThat(offsets.size, theme.name).isGreaterThanOrEqualTo(MIN_SKY_BANDS)
        }
    }

    @Test
    fun theDaySkyHasAPaleHorizonBandUnderADeeperZenith() {
        assertThat(luminance(day.sky.last().color)).isGreaterThan(luminance(day.sky.first().color))
        assertThat(
            day.sky
                .last()
                .color.blue -
                day.sky
                    .last()
                    .color.red,
        ).isLessThan(
            day.sky
                .first()
                .color.blue -
                day.sky
                    .first()
                    .color.red,
        )
    }

    @Test
    fun hazeIsNoneNearTheTeeAndGrowsWithDistanceToItsMaximum() {
        for (theme in RangeTheme.entries) {
            val haze = theme.style.haze
            assertThat(haze.amount(0.0), theme.name).isEqualTo(0f)
            assertThat(haze.amount(haze.startMeters), theme.name).isEqualTo(0f)
            var previous = 0f
            for (distance in listOf(50.0, 100.0, 200.0, 300.0, 400.0, 800.0, 2000.0, 6000.0)) {
                val amount = haze.amount(distance)
                assertThat(amount, "${theme.name} at $distance m").isGreaterThanOrEqualTo(previous)
                assertThat(amount, "${theme.name} at $distance m").isLessThan(haze.maxAmount + 1e-6f)
                previous = amount
            }
            assertThat(previous, theme.name).isGreaterThan(haze.maxAmount * 0.99f)
        }
    }

    @Test
    fun hazeMovesAColourTowardTheHazeKeepingItsAlpha() {
        val haze = RangeHaze(RangeColor(1f, 1f, 1f), startMeters = 0.0, depthMeters = 100.0, maxAmount = 1f)
        val base = RangeColor(0f, 0.5f, 0f, 0.4f)

        assertThat(haze.apply(base, 0.0)).isEqualTo(base)
        val far = haze.apply(base, 10_000.0)
        assertThat(far.red.toDouble()).isGreaterThan(0.99)
        assertThat(far.alpha).isEqualTo(0.4f)
        // Half strength (the markers) goes half as far.
        assertThat(haze.apply(base, 10_000.0, strength = 0.5f).red.toDouble()).isGreaterThan(0.49)
        assertThat(haze.apply(base, 10_000.0, strength = 0.5f).red.toDouble()).isLessThan(0.51)
    }

    @Test
    fun theDistantGroundIsTheRoughFullyHazed() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            assertThat(
                style.distantGround,
                theme.name,
            ).isEqualTo(style.ground.mix(style.haze.color, style.haze.maxAmount))
        }
    }

    @Test
    fun everyThemeHasASunInTheSkyAndRidgesFarToNear() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            assertThat(style.sun.elevationDegrees, theme.name).isGreaterThan(0.0)
            assertThat(style.sun.glowRadiusDegrees, theme.name).isGreaterThan(style.sun.discRadiusDegrees)
            val glowOffsets = style.sun.glow.map { it.offset }
            assertThat(glowOffsets, theme.name).isEqualTo(glowOffsets.sorted())
            assertThat(
                style.sun.glow
                    .last()
                    .color.alpha,
                theme.name,
            ).isEqualTo(0f)
            // The glow fades outwards.
            val alphas = style.sun.glow.map { it.color.alpha }
            assertThat(alphas, theme.name).isEqualTo(alphas.sortedDescending())
            // Far ridges first: the far one is lighter (hazier) than the near one in daylight
            // themes, and at night both are darker than the sky at the horizon.
            assertThat(style.ridges.size, theme.name).isEqualTo(2)
            val horizonSky = luminance(style.sky.last().color)
            assertThat(luminance(style.ridges[1].color), theme.name).isLessThan(horizonSky)
            assertThat(luminance(style.ridges[1].color), theme.name).isLessThan(luminance(style.ridges[0].color) + 1e-6)
        }
    }

    @Test
    fun theTreesAreThreeTonesFromShadeToSunlight() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            assertThat(luminance(style.crownDark), theme.name).isLessThan(luminance(style.crownMid))
            assertThat(luminance(style.crownMid), theme.name).isLessThan(luminance(style.crownLight))
        }
    }

    @Test
    fun theLabelsAndTracerStayReadableOnEveryTheme() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            // White labels over the fairway and rough (WCAG's large-text 3:1).
            assertThat(contrast(style.label, style.fairway), theme.name).isGreaterThan(LARGE_TEXT_CONTRAST)
            assertThat(contrast(style.label, style.ground), theme.name).isGreaterThan(LARGE_TEXT_CONTRAST)
            // The tracer's core against the sky at the horizon, where it spends most of its flight
            // on the follow camera, and against the fairway where it lands. A blue tracer on green
            // grass differs in hue rather than lightness, so this is a colour distance.
            for (background in listOf(style.sky.last().color, style.fairway, style.ground)) {
                assertThat(distance(style.tracer, background), theme.name).isGreaterThan(MIN_TRACER_DISTANCE)
            }
            assertThat(style.tracerGlowWidthFactor, theme.name).isGreaterThan(1f)
            assertThat(style.tracerGlow.alpha, theme.name).isLessThan(style.tracer.alpha)
        }
    }

    @Test
    fun theNightTracerIsBrightAndGlowsWider() {
        val night = RangeTheme.NIGHT.style
        assertThat(luminance(night.tracer)).isGreaterThan(0.5)
        assertThat(night.tracerGlowWidthFactor).isGreaterThan(day.tracerGlowWidthFactor)
        assertThat(contrast(night.tracer.withAlpha(1f), night.sky.first().color)).isGreaterThan(NIGHT_TRACER_CONTRAST)
    }

    @Test
    fun theDayTracerIsStillTheHighVisibilityStyle() {
        assertThat(day.tracer).isEqualTo(RangeColor(0.04f, 0.42f, 1f, 0.84f))
        assertThat(day.tracer).isEqualTo(RangeColor.of(RangeTracerStyle.highVisibility))
    }

    @Test
    fun everyThemeSharesTheMarkersLandingScrimAndSizes() {
        for (theme in RangeTheme.entries) {
            val style = theme.style
            assertThat(style.markerRed).isEqualTo(RangeColor(0.90f, 0.18f, 0.15f, 1f))
            assertThat(style.markerYellow).isEqualTo(RangeColor(0.96f, 0.72f, 0.08f, 1f))
            assertThat(style.markerOuter).isEqualTo(RangeColor(1f, 1f, 1f, 0.88f))
            assertThat(style.landingInner).isEqualTo(RangeColor(1f, 0.72f, 0.06f, 0.95f))
            assertThat(style.label).isEqualTo(RangeColor.WHITE)
            assertThat(style.overlaySelected).isEqualTo(RangeColor.fromArgb(0xFFD4AF37))
            assertThat(style.shade).containsExactly(
                RangeGradientStop(0f, RangeColor(0f, 0f, 0f, 0.38f)),
                RangeGradientStop(0.5f, RangeColor(0f, 0f, 0f, 0f)),
                RangeGradientStop(1f, RangeColor(0f, 0f, 0f, 0.60f)),
            )
            assertThat(style.maxLabelSize).isEqualTo(16f)
            assertThat(style.rollOutDashPixels).isEqualTo(10f)
            assertThat(style.ballRadiusMeters).isEqualTo(RangeProjection.BALL_RADIUS_METERS)
        }
    }

    @Test
    fun theSelectedOverlayGoldComesFromItsPackedArgb() {
        assertThat(RangeColor.fromArgb(0xFFD4AF37)).isEqualTo(RangeColor(212 / 255f, 175 / 255f, 55 / 255f, 1f))
    }

    @Test
    fun bothRenderersShareTheBalancedQualityProfile() {
        assertThat(RangeFrame.QUALITY).isEqualTo(RangeQualityProfile.BALANCED)
    }

    private fun luminance(color: RangeColor): Double {
        fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** The WCAG contrast ratio of two opaque colours. */
    private fun contrast(
        a: RangeColor,
        b: RangeColor,
    ): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    /** The straight-line distance between two colours' red, green and blue (0 to √3). */
    private fun distance(
        a: RangeColor,
        b: RangeColor,
    ): Double {
        val dr = (a.red - b.red).toDouble()
        val dg = (a.green - b.green).toDouble()
        val db = (a.blue - b.blue).toDouble()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private companion object {
        const val MIN_SKY_BANDS = 4
        const val LARGE_TEXT_CONTRAST = 3.0
        const val MIN_TRACER_DISTANCE = 0.5
        const val NIGHT_TRACER_CONTRAST = 7.0
    }
}
