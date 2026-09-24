// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

internal fun previewCameraState(
    phase: CameraPhase,
    availability: PiFeatureAvailability = PiFeatureAvailability.Available,
    cameraAvailable: Boolean = phase != CameraPhase.UNAVAILABLE,
    enabled: Boolean =
        phase == CameraPhase.PAUSED || phase == CameraPhase.STREAMING ||
            phase == CameraPhase.STREAM_ERROR,
    streaming: Boolean = phase == CameraPhase.STREAMING || phase == CameraPhase.STREAM_ERROR,
    ballDetected: Boolean = false,
    confidence: Int = 0,
): CameraUiState {
    val toggleCamera =
        when {
            !availability.isAvailable -> availability
            !cameraAvailable -> PiFeatureAvailability.Unavailable(CameraUiState.CAMERA_NOT_AVAILABLE)
            else -> PiFeatureAvailability.Available
        }
    return CameraUiState(
        phase = phase,
        availability = availability,
        cameraAvailable = cameraAvailable,
        enabled = enabled,
        streaming = streaming,
        ballDetected = ballDetected,
        ballConfidencePercent = confidence,
        statusText =
            when {
                !enabled -> "Camera Off"
                ballDetected -> "Ball $confidence%"
                else -> "No Ball"
            },
        cameraError = null,
        streamError = if (phase == CameraPhase.STREAM_ERROR) CameraUiState.CAMERA_NOT_AVAILABLE else null,
        toggleCamera = toggleCamera,
        toggleStream =
            if (toggleCamera.isAvailable && !enabled) {
                PiFeatureAvailability.Unavailable(CameraUiState.CAMERA_DISABLED)
            } else {
                toggleCamera
            },
    )
}

@Preview
@Composable
private fun CameraUnavailablePreview() {
    OfTheme {
        CameraScreen(uiState = previewCameraState(CameraPhase.UNAVAILABLE), frame = null, onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun CameraPausedPreview() {
    OfTheme {
        CameraScreen(
            uiState = previewCameraState(CameraPhase.PAUSED, ballDetected = true, confidence = 87),
            frame = null,
            onEvent = {},
            onBack = {},
        )
    }
}
