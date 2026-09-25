// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class CameraScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<CameraEvent>()

    private fun show(
        state: CameraUiState,
        frame: ImageBitmap? = null,
    ) {
        composeRule.setContent {
            OfTheme { CameraScreen(uiState = state, frame = frame, onEvent = { events += it }, onBack = {}) }
        }
    }

    @Test
    fun givenAPiWithoutCameraCapture_whenShown_thenTheFeedSaysSo() {
        show(previewCameraState(CameraPhase.NOT_ENABLED))

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Camera Capture Off")
        composeRule.onNodeWithTag(CameraTestTags.CAPTURE_STATUS).assertTextEquals("Not enabled on the Pi")
    }

    @Test
    fun givenNoLink_whenShown_thenTheFeedIsOfflineAndRefreshIsDisabled() {
        show(
            previewCameraState(
                CameraPhase.OFFLINE,
                availability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.REQUIRES_WIFI),
            ),
        )

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Camera Offline")
        composeRule.onNodeWithTag(CameraTestTags.REFRESH).assertIsNotEnabled()
    }

    @Test
    fun givenALiveStill_whenShown_thenItIsDrawn() {
        show(previewCameraState(CameraPhase.LIVE), frame = solidFrame())

        composeRule.onNodeWithTag(CameraTestTags.FRAME).assertIsDisplayed()
    }

    @Test
    fun givenAReplayableShot_whenPlayIsTapped_thenTheReplayIsRequested() {
        show(previewCameraState(CameraPhase.LIVE))

        composeRule.onNodeWithTag(CameraTestTags.replay("a1b2c3")).performScrollTo().performClick()

        assertEquals(listOf<CameraEvent>(CameraEvent.PlayReplay("a1b2c3")), events)
    }

    @Test
    fun givenAFailedReplay_whenShown_thenTheErrorShows() {
        val failed = CameraReplayState.Failed("a1b2c3", "Camera replay was not found")
        show(previewCameraState(CameraPhase.LIVE, replay = failed))

        composeRule.onNodeWithText("Camera replay was not found").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whenAJpegIsDecoded_thenAValidOneBecomesABitmapAndACorruptOneIsSkipped() {
        val jpeg =
            ByteArrayOutputStream().use { out ->
                Bitmap
                    .createBitmap(4, 3, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.GREEN) }
                    .compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }

        assertNotNull(decodeJpeg(jpeg))
        assertNull(decodeJpeg(byteArrayOf(1, 2, 3)))
    }

    private fun solidFrame(): ImageBitmap =
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }.asImageBitmap()
}
