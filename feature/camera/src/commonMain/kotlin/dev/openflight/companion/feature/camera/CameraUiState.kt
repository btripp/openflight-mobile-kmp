// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/** What the preview area shows (backend `2974e5d` camera API, plan R8c). */
enum class CameraPhase {
    /** No Socket.IO link (Bluetooth, or not connected yet): see [CameraUiState.availability]. */
    OFFLINE,

    /** Connected; the first preview still hasn't answered yet. */
    LOADING,

    /** [CameraViewModel.frames] is yielding stills. */
    LIVE,

    /** `404 "Camera capture not enabled"`: the Pi runs without high-speed camera capture. */
    NOT_ENABLED,

    /** `503 "Camera not running"`: capture is configured but the camera gave no still. */
    NOT_RUNNING,

    /** Any other failure; [CameraUiState.previewError] says what. Polling keeps retrying. */
    ERROR,
}

/**
 * One shot with a high-speed capture the Pi can replay (`ShotDetail.camera_replay`), newest first.
 *
 * @property shotNumber the Pi's `shot_number`, when it has one.
 */
data class ReplayRow(
    val replayId: String,
    val shotNumber: Int?,
    val timestamp: String,
    val club: String?,
    val ballSpeedMph: Double?,
    val mirrorHorizontal: Boolean,
)

/** The replay being prepared or played. */
sealed interface CameraReplayState {
    data object Idle : CameraReplayState

    /** `POST …/prepare` in flight (the Pi may be encoding the MP4). */
    data class Preparing(
        val replayId: String,
    ) : CameraReplayState

    /**
     * Ready to play: [videoUrl] is the absolute MP4 URL (HTTP Range supported) for a native
     * player; [mirrorHorizontal] is the Pi's `display_mirror_horizontal`.
     */
    data class Ready(
        val replayId: String,
        val videoUrl: String,
        val mirrorHorizontal: Boolean,
    ) : CameraReplayState

    data class Failed(
        val replayId: String,
        val message: String,
    ) : CameraReplayState
}

/**
 * The camera screen's state (plan R8c): a polled preview of the Pi's high-speed camera, its
 * capture settings, and the shots whose captures can be replayed. Wi-Fi only.
 *
 * @property settings the Pi's `camera_capture_settings`, or `null` before it answers.
 * @property previewError why the preview failed, in [CameraPhase.ERROR].
 */
data class CameraUiState(
    val phase: CameraPhase,
    val availability: PiFeatureAvailability,
    val settings: CameraCaptureSettings?,
    val previewError: String?,
    val replays: List<ReplayRow>,
    val replay: CameraReplayState,
) {
    /** "1456×1088 @ 240 fps" from [settings], when the Pi reports them. */
    val captureSummary: String?
        get() {
            val s = settings ?: return null
            val width = s.width ?: return null
            val height = s.height ?: return null
            val fps = s.fps?.let { " @ ${it.toInt()} fps" }.orEmpty()
            return "$width×$height$fps"
        }

    companion object {
        /** At most this many replayable shots are listed. */
        const val MAX_REPLAYS: Int = 10
    }
}

/** User intents from the camera screen, sent up to [CameraViewModel.onEvent]. */
sealed interface CameraEvent {
    /** `get_camera_capture_settings`. */
    data object RefreshSettings : CameraEvent

    /** Prepare and play one shot's replay. */
    data class PlayReplay(
        val replayId: String,
    ) : CameraEvent

    /** Closes the player or the replay error. */
    data object DismissReplay : CameraEvent
}

/** One-shot signals from [CameraViewModel]. */
sealed interface CameraEffect {
    /** A command failed, e.g. "Not connected to the Pi's live session yet." */
    data class Message(
        val text: String,
    ) : CameraEffect
}
