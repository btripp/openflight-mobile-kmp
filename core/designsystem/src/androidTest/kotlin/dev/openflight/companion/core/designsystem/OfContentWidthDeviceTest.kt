// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Plan F1b: [OfContentWidth] caps and centers a screen's content on a tablet, and is a no-op on a
 * phone. A phone emulator's own portrait width is already under the 840 dp cap, so the "tablet"
 * case rotates the activity to landscape instead of forcing a `Modifier` size: a forced size wider
 * than the device would just be coerced back down to its real screen bounds. Pixel tolerances allow
 * for the device's own density rounding.
 */
@RunWith(AndroidJUnit4::class)
class OfContentWidthDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(orientation: Int) {
        composeRule.activity.requestedOrientation = orientation
        composeRule.waitForIdle()
        composeRule.setContent {
            OfTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    OfContentWidth(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Float = with(composeRule.density) { dp.dp.toPx() }

    @Test
    fun givenAPhoneWidth_whenShown_thenTheContentFillsIt() {
        show(orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        val root = composeRule.onRoot().fetchSemanticsNode()
        val content = composeRule.onNodeWithTag(OfContentWidthTags.CONTENT).fetchSemanticsNode()
        assertTrue(root.size.width < dpToPx(OfContentMaxWidth.value.toInt()), "the device is unexpectedly wide")
        assertTrue(abs(content.size.width - root.size.width) <= TOLERANCE_PX)
    }

    @Test
    fun givenATabletWidth_whenShown_thenTheContentIsCappedAndCentered() {
        show(orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        val root = composeRule.onRoot().fetchSemanticsNode()
        val content = composeRule.onNodeWithTag(OfContentWidthTags.CONTENT).fetchSemanticsNode()
        assertTrue(root.size.width > dpToPx(OfContentMaxWidth.value.toInt()), "the device isn't wide enough rotated")
        assertTrue(abs(content.size.width - dpToPx(OfContentMaxWidth.value.toInt())) <= TOLERANCE_PX)
        val leftGap = content.boundsInRoot.left
        val rightGap = root.size.width - content.boundsInRoot.right
        assertTrue(abs(leftGap - rightGap) <= TOLERANCE_PX, "content isn't centered: $leftGap vs $rightGap")
    }

    private companion object {
        const val TOLERANCE_PX = 2f
    }
}
