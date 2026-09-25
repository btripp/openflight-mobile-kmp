// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A shot's high-speed camera capture (`ShotDetail.camera_replay`, backend camera/replay.py:103-111),
 * and the body of `POST /api/camera/replays/<id>/prepare` (server.py:1362), which adds [videoUrl].
 *
 * @property videoUrl the prepared MP4's path on the Pi (`/api/camera/replays/<id>/video`, served
 *   with HTTP Range support); only in the prepare response.
 * @property displayMirrorHorizontal whether a player should mirror the video (the operator view is
 *   mirrored unless the capture already was).
 */
@Serializable
data class CameraReplay(
    val id: String,
    @SerialName("frame_count") val frameCount: Int? = null,
    @SerialName("trigger_frame") val triggerFrame: Int? = null,
    @SerialName("playback_fps") val playbackFps: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("display_mirror_horizontal") val displayMirrorHorizontal: Boolean? = null,
    @SerialName("video_url") val videoUrl: String? = null,
)

/**
 * One `GET /api/camera/preview.jpg` (server.py:1337): a still from the capture runtime's
 * processed stream, served while the raw rolling buffer keeps running, so polling it never costs
 * a shot.
 */
sealed interface CameraPreview {
    /** `200 image/jpeg`. */
    class Frame(
        val jpeg: ByteArray,
    ) : CameraPreview {
        override fun equals(other: Any?): Boolean = other is Frame && jpeg.contentEquals(other.jpeg)

        override fun hashCode(): Int = jpeg.contentHashCode()
    }

    /** `404 "Camera capture not enabled"`: the Pi runs without high-speed camera capture. */
    data object CaptureNotEnabled : CameraPreview

    /** `503 "Camera not running"`: capture is configured but the camera produced no still. */
    data object CameraNotRunning : CameraPreview

    /** Any other answer, with the server's text. */
    data class Unavailable(
        val status: Int,
        val message: String,
    ) : CameraPreview
}

/**
 * `camera_capture_settings` (server.py:1447 `_camera_capture_settings_payload`): the high-speed
 * capture's configuration and live state. Without `--camera-capture` it is just
 * `{enabled: false, available: false, alignment_x_pct, alignment_y_pct}`. Only the fields a screen
 * needs are modelled; the rest of the dict is ignored.
 *
 * @property available whether the capture runtime is running on the Pi.
 * @property running/armed the runtime's state (`capture_runtime.status()`); `armed` once the
 *   rolling buffer holds enough pre-trigger frames.
 * @property alignmentXPct/alignmentYPct the alignment guide's position, 0-100 %.
 */
@Serializable
data class CameraCaptureSettings(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean? = null,
    val armed: Boolean? = null,
    val error: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    @SerialName("exposure_us") val exposureUs: Double? = null,
    val gain: Double? = null,
    @SerialName("auto_exposure_enabled") val autoExposureEnabled: Boolean? = null,
    @SerialName("alignment_x_pct") val alignmentXPct: Double? = null,
    @SerialName("alignment_y_pct") val alignmentYPct: Double? = null,
)
