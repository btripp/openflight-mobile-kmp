// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertTrue

/** Plan F8a2a: the range draws every theme, and follows the persisted theme through the view model. */
@RunWith(AndroidJUnit4::class)
class RangeThemeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenEachTheme_whenTheRangeIsDrawn_thenItPaintsThatThemesSkyAndGround() {
        var theme by mutableStateOf(RangeTheme.DAY)
        composeRule.setContent {
            OfTheme {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    RangeCanvas(
                        flight = null,
                        cameraMode = RangeCameraMode.FIXED,
                        reduceMotion = true,
                        onFlightCompleted = {},
                        modifier = Modifier.size(width = 360.dp, height = 640.dp),
                        theme = theme,
                    )
                }
            }
        }

        val tops = mutableListOf<Color>()
        for (next in RangeTheme.entries) {
            theme = next
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag(RangeTestTags.SCENE).captureToImage()
            val style = next.style

            // The top of the sky is the gradient's first band.
            val top = image.pixel(image.width / 2, 1)
            assertClose(style.sky.first().color, top, "${next.name} sky top")
            tops += top
            // Plan F8a2p: the raised tee camera looks down onto the rough just in front of it at the
            // bottom of a portrait canvas (too near for any haze: the flat rough colour).
            assertClose(style.ground, image.pixel(2, image.height - 2), "${next.name} rough")
            // Above the horizon the sky, the sun and the far ridges; below it the grass: the top
            // half is never one flat colour.
            assertTrue(image.distinctColors(column = image.width / 2, from = 0, to = image.height / 2) > 8, next.name)
        }
        for (i in tops.indices) {
            for (j in i + 1 until tops.size) assertTrue(tops[i] != tops[j], "themes $i and $j share a sky")
        }
    }

    @Test
    fun givenTheRange_whenTheStoredThemeBecomesNight_thenTheSceneRedrawsDarker() {
        val settings = FakeSettingsRepository()
        composeRule.setContent {
            OfTheme {
                val viewModel =
                    remember {
                        DrivingRangeViewModel(
                            FakeShotRepository(),
                            settings,
                            FakeShotHistoryRepository(),
                            FakeConditionsRepository(),
                            FakePiSessionRepository(),
                        )
                    }
                DrivingRangeRoute(onExit = {}, viewModel = viewModel, reduceMotion = true)
            }
        }
        composeRule.waitForIdle()
        val day = composeRule.onNodeWithTag(RangeTestTags.SCENE).captureToImage().meanLuminance()

        settings.rangeTheme.value = RangeThemeSetting.NIGHT
        composeRule.waitForIdle()
        val night = composeRule.onNodeWithTag(RangeTestTags.SCENE).captureToImage().meanLuminance()

        assertTrue(night < day * NIGHT_DARKER, "night $night vs day $day")
    }

    private fun ImageBitmap.pixel(
        x: Int,
        y: Int,
    ): Color = toPixelMap(startX = x, startY = y, width = 1, height = 1)[0, 0]

    private fun ImageBitmap.distinctColors(
        column: Int,
        from: Int,
        to: Int,
    ): Int {
        val pixels = toPixelMap(startX = column, startY = from, width = 1, height = to - from)
        return (0 until to - from).map { pixels[0, it] }.toSet().size
    }

    private fun ImageBitmap.meanLuminance(): Float {
        val pixels = toPixelMap()
        var sum = 0f
        var count = 0
        for (y in 0 until height step SAMPLE_STEP) {
            for (x in 0 until width step SAMPLE_STEP) {
                val color = pixels[x, y]
                sum += LUMA_RED * color.red + LUMA_GREEN * color.green + LUMA_BLUE * color.blue
                count++
            }
        }
        return sum / count
    }

    private fun assertClose(
        expected: RangeColor,
        actual: Color,
        what: String,
    ) {
        val close =
            abs(expected.red - actual.red) <= TOLERANCE &&
                abs(expected.green - actual.green) <= TOLERANCE &&
                abs(expected.blue - actual.blue) <= TOLERANCE
        assertTrue(close, "$what: expected $expected, drew $actual")
    }

    private companion object {
        /** A few 8-bit steps: the gradient's first pixel row and the display's colour conversion. */
        const val TOLERANCE = 6f / 255
        const val SAMPLE_STEP = 8
        const val NIGHT_DARKER = 0.6f
        const val LUMA_RED = 0.2126f
        const val LUMA_GREEN = 0.7152f
        const val LUMA_BLUE = 0.0722f
    }
}
