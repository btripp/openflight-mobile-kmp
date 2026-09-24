// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun toggle(tag: String) = composeRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun givenAPiWithoutACamera_whenShown_thenCameraNotAvailableAndTheTogglesAreDisabledWithTheReason() {
        show(previewCameraState(CameraPhase.UNAVAILABLE))

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Camera Not Available")
        toggle(CameraTestTags.TOGGLE_CAMERA).assertIsNotEnabled()
        toggle(CameraTestTags.TOGGLE_STREAM).assertIsNotEnabled()
        composeRule
            .onAllNodes(
                hasText(CameraUiState.CAMERA_NOT_AVAILABLE),
                useUnmergedTree = true,
            ).assertCountEquals(2)
        composeRule.onNodeWithText("Camera Off").assertIsDisplayed()
    }

    @Test
    fun givenNoLink_whenShown_thenTheFeedIsOfflineWithTheReason() {
        show(
            previewCameraState(
                CameraPhase.OFFLINE,
                availability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.REQUIRES_WIFI),
            ),
        )

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Camera Offline")
        toggle(CameraTestTags.TOGGLE_CAMERA).assertIsNotEnabled()
    }

    @Test
    fun givenADisabledCamera_whenBallDetectionIsToggled_thenToggleCameraIsSentAndStreamWaits() {
        show(previewCameraState(CameraPhase.DISABLED))

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Camera Disabled")
        toggle(CameraTestTags.TOGGLE_STREAM).assertIsNotEnabled()
        composeRule.onNodeWithText(CameraUiState.CAMERA_DISABLED).assertIsDisplayed()
        toggle(CameraTestTags.TOGGLE_CAMERA).assertIsEnabled().performClick()

        assertEquals(listOf<CameraEvent>(CameraEvent.ToggleCamera), events)
    }

    @Test
    fun givenAPausedStreamWithABall_whenTheStreamIsToggled_thenToggleStreamIsSent() {
        show(previewCameraState(CameraPhase.PAUSED, ballDetected = true, confidence = 87))

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Stream Paused")
        composeRule.onNodeWithText("Ball 87%").assertIsDisplayed()
        composeRule.onNodeWithText("Confidence 87%").assertIsDisplayed()
        toggle(CameraTestTags.TOGGLE_STREAM).performClick()

        assertEquals(listOf<CameraEvent>(CameraEvent.ToggleStream), events)
    }

    @Test
    fun givenAStreamError_whenRetryIsTapped_thenTheStreamIsRetried() {
        show(previewCameraState(CameraPhase.STREAM_ERROR))

        composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertTextEquals("Stream Error")
        composeRule.onNodeWithTag(CameraTestTags.RETRY).performClick()

        assertEquals(listOf<CameraEvent>(CameraEvent.RetryStream), events)
    }

    @Test
    fun givenStreamingWithAFrame_whenShown_thenTheFrameIsDrawn() {
        val frame = decodeJpeg(jpegOf(Color.GREEN))
        show(previewCameraState(CameraPhase.STREAMING), frame = frame)

        composeRule.onNodeWithTag(CameraTestTags.FRAME).assertIsDisplayed()
    }

    @Test
    fun givenStreamingBeforeTheFirstFrame_whenShown_thenNoFrameIsDrawn() {
        show(previewCameraState(CameraPhase.STREAMING))

        composeRule.onAllNodes(hasTestTag(CameraTestTags.FRAME)).assertCountEquals(0)
        composeRule.onNodeWithTag(CameraTestTags.FEED).assertIsDisplayed()
    }

    @Test
    fun givenAValidJpeg_whenDecoded_thenItsSizeIsKept() {
        val bitmap = assertNotNull(decodeJpeg(jpegOf(Color.RED)))

        assertEquals(FRAME_WIDTH, bitmap.width)
        assertEquals(FRAME_HEIGHT, bitmap.height)
    }

    @Test
    fun givenATruncatedJpeg_whenDecoded_thenItIsSkipped() {
        assertNull(decodeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)))
    }

    private fun jpegOf(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val FRAME_WIDTH = 64
        const val FRAME_HEIGHT = 48
        const val JPEG_QUALITY = 80
    }
}
