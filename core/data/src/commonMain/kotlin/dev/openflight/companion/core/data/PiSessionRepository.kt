// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.ProfileRules
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerStatus
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
 * (e.g. `delete_shot` → [deletionState] and a new [sessionShots]) or as a [notices] entry. They
 * are sent only while the link is connected, so nothing is buffered and replayed after a
 * reconnect (Expo `socket.ts` `emitWhileConnected`).
 *
 * Switching to another host (or to Bluetooth) resets every flow; a transient drop or [stop] keeps
 * them, so the last roster, power reading and club stay on screen until the reconnect's snapshots
 * replace them.
 *
 * Lifecycle methods must be called from one thread (the main thread), like a ViewModel's.
 */
@Suppress("TooManyFunctions", "ComplexInterface") // One command per server event, as in socketService.ts.
interface PiSessionRepository {
    val linkState: StateFlow<PiLinkState>

    /**
     * The Pi's current session for **every profile**, newest first, capped at [MAX_SESSION_SHOTS]
     * like the web UI. Filter it with `core:insights`' `forProfile`. A `shot_update` replaces its
     * `shot` in place (matched on `shot_number`, then `timestamp`); one for a shot never seen is
     * prepended.
     */
    val sessionShots: StateFlow<List<ShotDetail>>

    /**
     * Enrichment index for SSE/BLE shots: every [ShotDetail] seen on this host, keyed by
     * [ShotDetail.timestamp] (the only value `shot_to_dict` and the SSE `build_shot_event` share;
     * the SSE `event_id` is a fresh UUID the Socket.IO payload never carries). Survives
     * `clear_session`/`delete_shot`, so older history rows keep their detail; capped at
     * [MAX_SESSION_SHOTS] most recent. Use [detailFor].
     */
    val shotDetails: StateFlow<Map<String, ShotDetail>>

    /**
     * Server-computed stats over **every profile**, or `null` before the first `session_state` and
     * after a per-profile `session_cleared` that left rows (the server sends no stats with it).
     * Per-profile stats are computed on the client.
     */
    val stats: StateFlow<SessionStats?>

    /**
     * The Pi's roster (`profiles`), applied verbatim; requested on every connect. Kept through a
     * transient drop, reset on a host switch.
     */
    val profiles: StateFlow<ProfilesState>

    /**
     * The club the Pi files shots under: from `session_state.club` and every `club_changed`
     * broadcast (this phone's, the kiosk's or a simulator's). `null` until reported; never set
     * from a local pick, since the Pi ignores an unknown club without replying.
     */
    val club: StateFlow<String?>

    /** `shot_processing` (rolling-buffer only); cleared by the next `shot`. */
    val shotProcessing: StateFlow<ShotProcessingState?>

    /**
     * The last `power_status` (a complete snapshot, applied verbatim), or `null` until one arrives,
     * which is also the "not loaded" state. A Pi without `--battery` never sends one.
     */
    val powerStatus: StateFlow<PowerStatus?>

    /** The one [deleteShot] in flight and its outcome. */
    val deletionState: StateFlow<DeletionState>

    /** The last [clearSession] and its outcome. */
    val clearState: StateFlow<ClearState>

    /** The last `training_implement_changed`; the server doesn't report it on connect. */
    val trainingImplement: StateFlow<TrainingImplement?>

    /** The latest swing-speed rep (swing-speed mode only). */
    val latestSwingSpeed: StateFlow<SwingSpeedReading?>

    val triggerStatus: StateFlow<TriggerStatus?>

    /**
     * The high-speed capture's settings and state (`camera_capture_settings`), requested on every
     * connect; `null` until the Pi answers.
     */
    val cameraCaptureSettings: StateFlow<CameraCaptureSettings?>
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

    /**
     * `delete_shot` by [ShotDetail.timestamp] (not profile-scoped), tracked in [deletionState]:
     * [DeletionState.Pending] until a `session_state` without that timestamp
     * ([DeletionState.Deleted]) or a `delete_shot_error` / a dropped link ([DeletionState.Failed]).
     * Nothing is removed locally before the server confirms. Ignored while another deletion is
     * pending, since neither reply names its shot.
     */
    suspend fun deleteShot(timestamp: String)

    /** Returns [deletionState] to [DeletionState.Idle] once its outcome was shown. */
    fun dismissDeletion()

    /**
     * `clear_session {profile_id}`: removes one profile's rows, tracked in [clearState]. The
     * `session_cleared` broadcast carries the remaining session, which replaces [sessionShots]
     * whoever asked. Fails after [ClearState.TIMEOUT_MILLIS] without a confirmation or when the
     * link drops. Ignored while another clear is pending.
     *
     * @throws IllegalArgumentException for a blank [profileId] (the server would clear the
     *   active profile instead).
     */
    suspend fun clearSession(profileId: String)

    /** Returns [clearState] to [ClearState.Idle] once its outcome was shown. */
    fun dismissClear()

    /** `set_active_profile` → `profiles`. */
    suspend fun setActiveProfile(profileId: String)

    /**
     * `add_profile` with the trimmed [name] → `profiles`; the server makes the new profile active,
     * so no `set_active_profile` follows.
     *
     * @throws ProfileRuleException for a blank or too-long name, or at [ProfileRules.MAX_PROFILES].
     */
    suspend fun addProfile(name: String)

    /**
     * `rename_profile` with the trimmed [name] → `profiles`.
     *
     * @throws ProfileRuleException for a blank or too-long name.
     */
    suspend fun renameProfile(
        profileId: String,
        name: String,
    )

    /**
     * `remove_profile` → `profiles`. The server refuses the active profile, the last one and one
     * with session rows, answering with the unchanged roster.
     */
    suspend fun removeProfile(profileId: String)

    /** `simulate_shot`: works only on a `--mock` Pi; a real Pi ignores it. */
    suspend fun simulateShot()

    /** `set_training_implement` (a [TrainingImplement.KNOWN] key) → `training_implement_changed`. */
    suspend fun setTrainingImplement(implement: String)

    /** `get_camera_capture_settings` → [cameraCaptureSettings]. Sent automatically on every connect. */
    suspend fun refreshCameraCaptureSettings()

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
     * One still from `GET /api/camera/preview.jpg` on the Wi-Fi host. The caller polls it, only
     * while its screen is visible. Works whenever the host is set on Wi-Fi (plain HTTP, not the
     * Socket.IO link).
     *
     * @throws WifiOnlyFeatureException on Bluetooth or without a host; network failures propagate.
     */
    suspend fun cameraPreview(): CameraPreview

    /**
     * Prepares shot replay [replayId] (`ShotDetail.cameraReplay.id`) and returns the absolute URL
     * of its MP4 for a native player.
     *
     * @throws WifiOnlyFeatureException on Bluetooth or without a host.
     * @throws IllegalStateException when the Pi's answer carries no video URL; HTTP errors propagate.
     */
    suspend fun prepareReplay(replayId: String): String

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

/** A profile mutation broke one of the server's [ProfileRules]; nothing was sent. */
class ProfileRuleException(
    val rule: Rule,
) : IllegalArgumentException(rule.message) {
    enum class Rule(
        val message: String,
    ) {
        BLANK_NAME("Enter a name for the profile."),
        NAME_TOO_LONG("Profile names can be at most ${ProfileRules.MAX_NAME_LENGTH} characters."),
        TOO_MANY_PROFILES(
            "The Pi holds at most ${ProfileRules.MAX_PROFILES} profiles. Remove one to add another.",
        ),
    }
}
