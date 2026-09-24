// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/**
 * What the camera screen shows, following the web UI's `CameraFeed.tsx` branches in order.
 * [STREAMING] is the only phase in which [CameraViewModel.frames] yields JPEGs.
 */
enum class CameraPhase {
    /** No Socket.IO link (Bluetooth, or not connected yet): see [CameraUiState.availability]. */
    OFFLINE,

    /** "Camera Not Available": the Pi runs without `--camera`. */
    UNAVAILABLE,

    /** "Camera Disabled": ball detection is off. */
    DISABLED,

    /** "Stream Paused": ball detection runs, the live feed is off. */
    PAUSED,

    STREAMING,

    /** "Stream Error": the MJPEG stream failed; [CameraEvent.RetryStream] reopens it. */
    STREAM_ERROR,
}

/**
 * The camera screen's state (plan R6b), ported from the web UI's `CameraFeed.tsx` and
 * `BallDetectionIndicator.tsx`. Wi-Fi only.
 *
 * @property ballConfidencePercent the detection confidence, rounded to a whole percent.
 * @property statusText the ball indicator's text: "Camera Off", "Ball 87%" or "No Ball".
 * @property cameraError the Pi's latest `camera_status` error (e.g. "Camera not initialized").
 * @property streamError why the MJPEG stream failed (e.g. "Camera not available"), in [CameraPhase.STREAM_ERROR].
 * @property toggleCamera enabling or disabling ball detection; needs the link and a camera.
 * @property toggleStream starting or stopping the live feed; also needs the camera enabled.
 */
data class CameraUiState(
    val phase: CameraPhase,
    val availability: PiFeatureAvailability,
    val cameraAvailable: Boolean,
    val enabled: Boolean,
    val streaming: Boolean,
    val ballDetected: Boolean,
    val ballConfidencePercent: Int,
    val statusText: String,
    val cameraError: String?,
    val streamError: String?,
    val toggleCamera: PiFeatureAvailability,
    val toggleStream: PiFeatureAvailability,
) {
    companion object {
        const val CAMERA_NOT_AVAILABLE: String = "Camera not available"
        const val CAMERA_DISABLED: String = "Camera disabled"
    }
}

/** User intents from the camera screen, sent up to [CameraViewModel.onEvent]. */
sealed interface CameraEvent {
    /** `toggle_camera`: ball detection on or off. */
    data object ToggleCamera : CameraEvent

    /** `toggle_camera_stream`: the live feed on or off. */
    data object ToggleStream : CameraEvent

    /** Reopens the MJPEG stream after [CameraPhase.STREAM_ERROR] (the web UI's "Retry"). */
    data object RetryStream : CameraEvent
}

/** One-shot signals from [CameraViewModel]. */
sealed interface CameraEffect {
    /** A command failed, e.g. "Not connected to the Pi's live session yet." */
    data class Message(
        val text: String,
    ) : CameraEffect
}
