// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.flight.RangeQualityProfile
import kotlin.test.Test

/**
 * Plan F8c1: [RangeTheme.DAY] holds exactly the colours and sizes Android drew before the palette
 * moved to shared code (RangeScene.kt, RangeRenderer.kt, RangeCanvas.kt and DrivingRangeScreen's
 * Shade at 05ecb23), so iOS can paint the same range.
 */
class RangeVisualStyleTest {
    private val day = RangeTheme.DAY.style

    @Test
    fun theDayPaletteIsTheOriginalAndroidPalette() {
        assertThat(day.skyTop).isEqualTo(RangeColor(0.34f, 0.63f, 0.88f, 1f))
        assertThat(day.skyHorizon).isEqualTo(RangeColor(0.68f, 0.84f, 0.95f, 1f))
        assertThat(day.ground).isEqualTo(RangeColor(0.08f, 0.30f, 0.14f, 1f))
        assertThat(day.fairway).isEqualTo(RangeColor(0.20f, 0.52f, 0.22f, 1f))
        assertThat(day.stripe).isEqualTo(RangeColor(0.25f, 0.59f, 0.27f, 1f))
        assertThat(day.targetLine).isEqualTo(RangeColor(1f, 1f, 1f, 0.35f))
        assertThat(day.tee).isEqualTo(RangeColor(0.16f, 0.47f, 0.20f, 1f))
        assertThat(day.teeMarker).isEqualTo(RangeColor(1f, 1f, 1f, 1f))
        assertThat(day.markerOuter).isEqualTo(RangeColor(1f, 1f, 1f, 0.88f))
        assertThat(day.markerRed).isEqualTo(RangeColor(0.90f, 0.18f, 0.15f, 1f))
        assertThat(day.markerYellow).isEqualTo(RangeColor(0.96f, 0.72f, 0.08f, 1f))
        assertThat(day.trunk).isEqualTo(RangeColor(0.29f, 0.16f, 0.08f, 1f))
        assertThat(day.crownDark).isEqualTo(RangeColor(0.05f, 0.26f, 0.10f, 1f))
        assertThat(day.crownLight).isEqualTo(RangeColor(0.07f, 0.34f, 0.13f, 1f))
        assertThat(day.landingOuter).isEqualTo(RangeColor(1f, 1f, 1f, 0.85f))
        assertThat(day.landingInner).isEqualTo(RangeColor(1f, 0.72f, 0.06f, 0.95f))
        assertThat(day.shadow).isEqualTo(RangeColor(0f, 0f, 0f, 0.28f))
        assertThat(day.rollOut).isEqualTo(RangeColor(1f, 1f, 1f, 0.9f))
        assertThat(day.ball).isEqualTo(RangeColor.WHITE)
        assertThat(day.label).isEqualTo(RangeColor.WHITE)
    }

    @Test
    fun theTracerIsTheHighVisibilityStyle() {
        assertThat(day.tracer).isEqualTo(RangeColor(0.04f, 0.42f, 1f, 0.84f))
    }

    @Test
    fun theSelectedOverlayGoldComesFromItsPackedArgb() {
        assertThat(RangeColor.fromArgb(0xFFD4AF37)).isEqualTo(RangeColor(212 / 255f, 175 / 255f, 55 / 255f, 1f))
        assertThat(day.overlaySelected).isEqualTo(RangeColor.fromArgb(0xFFD4AF37))
    }

    @Test
    fun theShadeDarkensTheTopAndBottomAndClearsTheMiddle() {
        assertThat(day.shade).containsExactly(
            RangeGradientStop(0f, RangeColor(0f, 0f, 0f, 0.38f)),
            RangeGradientStop(0.5f, RangeColor(0f, 0f, 0f, 0f)),
            RangeGradientStop(1f, RangeColor(0f, 0f, 0f, 0.60f)),
        )
    }

    @Test
    fun theLayoutSizesAreTheOriginalOnes() {
        assertThat(day.overlayTracerAlpha).isEqualTo(0.7f)
        assertThat(day.labelHeightMeters).isEqualTo(2.2f)
        assertThat(day.minLabelSize).isEqualTo(9f)
        assertThat(day.maxLabelSize).isEqualTo(16f)
        assertThat(day.rollOutLabelSize).isEqualTo(13f)
        assertThat(day.overlayStrokeWidth).isEqualTo(2f)
        assertThat(day.selectedStrokeWidth).isEqualTo(4f)
        assertThat(day.overlayDotRadius).isEqualTo(3f)
        assertThat(day.rollOutStrokeWidth).isEqualTo(2.5f)
        assertThat(day.rollOutDotRadius).isEqualTo(5f)
        assertThat(day.rollOutDashPixels).isEqualTo(10f)
        assertThat(day.rollOutGapPixels).isEqualTo(8f)
        assertThat(day.ballRadiusMeters).isEqualTo(RangeProjection.BALL_RADIUS_METERS)
    }

    @Test
    fun bothRenderersShareTheBalancedQualityProfile() {
        assertThat(RangeFrame.QUALITY).isEqualTo(RangeQualityProfile.BALANCED)
    }
}
