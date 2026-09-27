// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.designsystem.OfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan F8a2t: the range draws the chosen shot trail through the shared [ShotTrail], and the Settings
 * preview ([ShotTrailPreviewCanvas]) shows the picker's choice with the range's own renderer.
 */
@RunWith(AndroidJUnit4::class)
class ShotTrailDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenALandedFlight_whenTheTrailBecomesNeon_thenTheRangeDrawsItPink() {
        var trail by mutableStateOf(RangeTrailState())
        val flight = ShotTrailPreview.flight(1)
        composeRule.setContent {
            OfTheme {
                RangeCanvas(
                    flight = flight,
                    cameraMode = RangeCameraMode.FIXED,
                    reduceMotion = true,
                    onFlightCompleted = {},
                    modifier = Modifier.size(width = 360.dp, height = 640.dp),
                    view = ShotTrailPreview.VIEW,
                    freezeProgress = 1f,
                    trail = trail,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, scene(RangeTestTags.SCENE).pinkPixels(), "classic has no neon pink")

        trail = RangeTrailState(style = ShotTrailStyle.NEON)
        composeRule.waitForIdle()

        assertTrue(scene(RangeTestTags.SCENE).pinkPixels() > MIN_TRAIL_PIXELS, "neon draws its pink tube")
    }

    @Test
    fun givenThePreview_whenKeepLastAndALandingRingAreOn_thenMoreIsDrawn() {
        var keepLast by mutableStateOf(0)
        var effect by mutableStateOf(LandingEffect.OFF)
        composeRule.setContent {
            OfTheme {
                ShotTrailPreviewCanvas(
                    style = ShotTrailStyle.CLASSIC,
                    keepLast = keepLast,
                    landingEffect = effect,
                    theme = RangeThemeSetting.DAY,
                    modifier = Modifier.size(width = 360.dp, height = 200.dp),
                    reduceMotion = true,
                )
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) { scene(RangeTestTags.TRAIL_PREVIEW).bluePixels() > MIN_TRAIL_PIXELS }
        val alone = scene(RangeTestTags.TRAIL_PREVIEW)

        keepLast = 3
        composeRule.waitForIdle()
        val kept = scene(RangeTestTags.TRAIL_PREVIEW)
        assertTrue(kept.bluePixels() > alone.bluePixels() + MIN_TRAIL_PIXELS, "the three earlier trails show")

        effect = LandingEffect.RING
        composeRule.waitForIdle()
        assertTrue(scene(RangeTestTags.TRAIL_PREVIEW).differsFrom(kept) > MIN_TRAIL_PIXELS, "the landing ring shows")
    }

    private fun scene(tag: String): ImageBitmap = composeRule.onNodeWithTag(tag).captureToImage()

    private fun ImageBitmap.count(matches: (Float, Float, Float) -> Boolean): Int {
        val pixels = toPixelMap()
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = pixels[x, y]
                if (matches(c.red, c.green, c.blue)) count++
            }
        }
        return count
    }

    /** The neon tube's hot pink (1, 0.16, 0.78), not in any theme's scene. */
    private fun ImageBitmap.pinkPixels(): Int = count { r, g, b -> r > 0.8f && g < 0.4f && b > 0.55f }

    /** The DAY tracer's saturated blue, not in the scene (the sky is paler). */
    private fun ImageBitmap.bluePixels(): Int = count { r, g, b -> b > 0.7f && r < 0.3f && g < 0.6f }

    private fun ImageBitmap.differsFrom(other: ImageBitmap): Int {
        val mine = toPixelMap()
        val theirs = other.toPixelMap()
        var count = 0
        for (y in 0 until height) for (x in 0 until width) if (mine[x, y] != theirs[x, y]) count++
        return count
    }

    private companion object {
        const val MIN_TRAIL_PIXELS = 40
        const val TIMEOUT_MILLIS = 10_000L
    }
}
