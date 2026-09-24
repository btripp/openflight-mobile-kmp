// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.CameraStatus
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Pi's live-session API over Socket.IO (plan R6a): everything the Pi's web UI can see and do
 * beyond the SSE/BLE shot stream. **Wi-Fi only.**
 *
 * While started and [SettingsRepository.transport] is Wi-Fi, it keeps one Socket.IO connection to
 * the configured host (same host and port as the HTTP API) and mirrors the server's broadcasts
 * into these flows. On Bluetooth [linkState] is [PiLinkState.WifiOnly], every flow is reset to its
 * empty value, and every command throws [WifiOnlyFeatureException].
 *
 * Commands are fire-and-forget emits, like the web UI's: the result arrives as a state change
 * (e.g. `delete_shot` → a new [sessionShots]) or as a [notices] entry (e.g. "Shot not found").
 *
 * Lifecycle methods must be called from one thread (the main thread), like a ViewModel's.
 */
@Suppress("TooManyFunctions", "ComplexInterface") // One command per server event, as in socketService.ts.
interface PiSessionRepository {
    val linkState: StateFlow<PiLinkState>

    /** The Pi's current session, **newest first**, capped at [MAX_SESSION_SHOTS] like the web UI. */
    val sessionShots: StateFlow<List<ShotDetail>>

    /**
     * Enrichment index for SSE/BLE shots: every [ShotDetail] seen on this host, keyed by
     * [ShotDetail.timestamp] (the only value `shot_to_dict` and the SSE `build_shot_event` share;
     * the SSE `event_id` is a fresh UUID the Socket.IO payload never carries). Survives
     * `clear_session`/`delete_shot`, so older history rows keep their detail; capped at
     * [MAX_SESSION_SHOTS] most recent. Use [detailFor].
     */
    val shotDetails: StateFlow<Map<String, ShotDetail>>

    /** Server-computed session stats, or `null` before the first `session_state`. */
    val stats: StateFlow<SessionStats?>

    val playerName: StateFlow<String?>

    /** The last `training_implement_changed`; the server doesn't report it on connect. */
    val trainingImplement: StateFlow<TrainingImplement?>

    /** The latest swing-speed rep (swing-speed mode only). */
    val latestSwingSpeed: StateFlow<SwingSpeedReading?>

    val triggerStatus: StateFlow<TriggerStatus?>
    val cameraStatus: StateFlow<CameraStatus>
    val simState: StateFlow<SimState>
    val radarConfig: StateFlow<RadarConfig?>
    val debugState: StateFlow<DebugState>
    val cloudUploadStatus: StateFlow<CloudUploadStatus>

    /** Whether the Pi runs `--mock` (from the on-connect `session_state`); `null` until known. */
    val mockMode: StateFlow<Boolean?>

    /** Server errors and acknowledgements, one-shot (not replayed). */
    val notices: SharedFlow<PiNotice>

    /** The Socket.IO detail for an SSE/BLE shot, matched on timestamp. */
    fun detailFor(shot: ShotEvent): ShotDetail? = shotDetails.value[shot.timestamp]

    /** App foreground: follow settings and connect when on Wi-Fi. Idempotent. */
    fun start()

    /** App background: disconnect and stop following settings. */
    fun stop()

    // Commands. Each throws WifiOnlyFeatureException on Bluetooth or while the link isn't connected.

    /** `get_session` → `session_state`. Sent automatically on every connect. */
    suspend fun refreshSession()

    /** `delete_shot` by [ShotDetail.timestamp] → `session_state`, or a [PiNotice.DeleteShotFailed]. */
    suspend fun deleteShot(timestamp: String)

    /** `clear_session` → `session_cleared`. */
    suspend fun clearSession()

    /** `simulate_shot`: works only on a `--mock` Pi; a real Pi ignores it. */
    suspend fun simulateShot()

    /** `set_player` → `player_changed`. The server trims to 40 chars and uses "Player 1" for blank. */
    suspend fun setPlayer(name: String)

    /** `set_training_implement` (a [TrainingImplement.KNOWN] key) → `training_implement_changed`. */
    suspend fun setTrainingImplement(implement: String)

    /** `toggle_camera` → `camera_status`. */
    suspend fun toggleCamera()

    /** `toggle_camera_stream` → `camera_status`; while streaming, [cameraFrames] yields JPEGs. */
    suspend fun toggleCameraStream()

    /** `get_camera_status` → `camera_status`. */
    suspend fun refreshCameraStatus()

    /** `get_radar_config` → `radar_config`. Sent automatically on every connect. */
    suspend fun refreshRadarConfig()

    /** `set_radar_config` with only [update]'s non-null fields → `radar_config`, or a [PiNotice.RadarConfigFailed]. */
    suspend fun setRadarConfig(update: RadarConfigUpdate)

    /** `toggle_debug` → `debug_toggled`. */
    suspend fun toggleDebug()

    /** `upload_cloud` → `cloud_upload_status` (running, then complete or error). */
    suspend fun uploadCloud()

    /** `shutdown` → [PiNotice.ShuttingDown], then the Pi powers its services down. */
    suspend fun shutdown()

    /**
     * JPEG frames from `GET /camera/stream` (MJPEG) while the camera is enabled and streaming.
     * Cold: each collection opens its own HTTP stream.
     *
     * @throws WifiOnlyFeatureException on Bluetooth or when stopped.
     * @throws PiCameraUnavailableException when the Pi answers with an error (503 "Camera not available").
     */
    fun cameraFrames(): Flow<ByteArray>

    companion object {
        const val MAX_SESSION_SHOTS: Int = 200
    }
}

/** A Wi-Fi-only (Socket.IO) feature was used on Bluetooth, or while the link to the Pi is down. */
class WifiOnlyFeatureException(
    val reason: Reason,
) : IllegalStateException(reason.message) {
    enum class Reason(
        val message: String,
    ) {
        BLUETOOTH("This feature needs the Pi over Wi-Fi. Switch the transport to Wi-Fi to use it."),
        NOT_CONNECTED("Not connected to the Pi's live session yet."),
    }
}

/** `GET /camera/stream` failed: the Pi's camera is missing, disabled or not streaming. */
class PiCameraUnavailableException(
    val status: Int,
    message: String,
) : IllegalStateException(message)
