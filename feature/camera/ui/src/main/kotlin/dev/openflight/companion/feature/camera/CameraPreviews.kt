// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

internal fun previewCameraState(
    phase: CameraPhase,
    availability: PiFeatureAvailability = PiFeatureAvailability.Available,
    settings: CameraCaptureSettings? =
        if (phase == CameraPhase.NOT_ENABLED) {
            CameraCaptureSettings(available = false)
        } else {
            CameraCaptureSettings(
                available = true,
                enabled = true,
                running = true,
                armed = true,
                width = 1456,
                height = 1088,
                fps = 240.0,
            )
        },
    replays: List<ReplayRow> = listOf(previewReplayRow()),
    replay: CameraReplayState = CameraReplayState.Idle,
    previewError: String? = if (phase == CameraPhase.ERROR) "Camera exploded (HTTP 500)" else null,
): CameraUiState =
    CameraUiState(
        phase = phase,
        availability = availability,
        settings = settings,
        previewError = previewError,
        replays = replays,
        replay = replay,
    )

internal fun previewReplayRow(id: String = "a1b2c3"): ReplayRow =
    ReplayRow(
        replayId = id,
        shotNumber = 7,
        timestamp = "2026-09-25T10:03:35.906612",
        club = "driver",
        ballSpeedMph = 151.4,
        mirrorHorizontal = true,
    )

@Preview
@Composable
private fun CameraNotEnabledPreview() {
    OfTheme {
        CameraScreen(uiState = previewCameraState(CameraPhase.NOT_ENABLED), frame = null, onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun CameraReplayReadyPreview() {
    OfTheme {
        CameraScreen(
            uiState =
                previewCameraState(
                    CameraPhase.LOADING,
                    replay =
                        CameraReplayState.Ready("a1b2c3", "http://pi.local:8080/api/camera/replays/a1b2c3/video", true),
                ),
            frame = null,
            onEvent = {},
            onBack = {},
        )
    }
}
