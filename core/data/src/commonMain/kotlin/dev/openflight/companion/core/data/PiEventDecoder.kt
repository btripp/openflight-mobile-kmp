// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.CameraCaptureSettings
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugReading
import dev.openflight.companion.core.model.pi.DebugShotLog
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.ProfilesSnapshot
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.SessionCleared
import dev.openflight.companion.core.model.pi.SessionState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.model.pi.SimPlayer
import dev.openflight.companion.core.model.pi.SimShot
import dev.openflight.companion.core.model.pi.SimStatus
import dev.openflight.companion.core.model.pi.SwingSpeedReading
import dev.openflight.companion.core.model.pi.TrainingImplement
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.socketio.SocketEvent
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lenient JSON for the Pi's Socket.IO payloads: unknown keys are ignored, a `null` or unknown enum
 * value for a field with a default takes the default, and Python's `NaN`/`Infinity` (which
 * `json.dumps` allows by default) decode instead of failing.
 */
internal val PiJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

/** One decoded server event (server.py `socketio.emit(...)` names). */
internal sealed interface PiEvent {
    data class Shot(
        val detail: ShotDetail,
        val stats: SessionStats?,
    ) : PiEvent

    /** `shot_update`: the enriched (or enrichment-skipped) final version of a shot already sent. */
    data class ShotUpdate(
        val detail: ShotDetail,
        val stats: SessionStats?,
    ) : PiEvent

    data class Processing(
        val state: ShotProcessingState,
    ) : PiEvent

    data class SwingSpeed(
        val reading: SwingSpeedReading,
        val stats: SessionStats?,
    ) : PiEvent

    data class Session(
        val state: SessionState,
    ) : PiEvent

    data class Cleared(
        val cleared: SessionCleared,
    ) : PiEvent

    data class Profiles(
        val snapshot: ProfilesSnapshot,
    ) : PiEvent

    /** Plan R8e: a v2 shot over BLE; only indexed for [PiSessionRepository.detailFor]. */
    data class BluetoothShot(
        val detail: ShotDetail,
    ) : PiEvent

    /** Plan R8e: the v2 `shot_deleted {timestamp}` event (BLE v2, SSE `?schema=2`). */
    data class ShotDeleted(
        val timestamp: String,
    ) : PiEvent

    data class Power(
        val status: PowerStatus,
    ) : PiEvent

    /** `club_changed` with a string club; any other payload is dropped by the decoder. */
    data class ClubChanged(
        val club: String,
    ) : PiEvent

    /** `delete_shot_error`; [error] is `null` when the payload had no usable message. */
    data class DeleteShotFailed(
        val error: String?,
    ) : PiEvent

    data class TrainingImplementChanged(
        val implement: TrainingImplement,
    ) : PiEvent

    data class Trigger(
        val status: TriggerStatus,
    ) : PiEvent

    data class Diagnostic(
        val diagnostic: TriggerDiagnostic,
    ) : PiEvent

    data class CameraSettings(
        val settings: CameraCaptureSettings,
    ) : PiEvent

    data class Sim(
        val status: SimStatus,
    ) : PiEvent

    data class SimShotSent(
        val shot: SimShot,
    ) : PiEvent

    data class SimPlayerChanged(
        val player: SimPlayer,
    ) : PiEvent

    data class Radar(
        val config: RadarConfig,
    ) : PiEvent

    data class Debug(
        val enabled: Boolean,
        val logPath: String?,
    ) : PiEvent

    data class DebugReadingReceived(
        val reading: DebugReading,
    ) : PiEvent

    data class DebugShotReceived(
        val log: DebugShotLog,
    ) : PiEvent

    data class Cloud(
        val status: CloudUploadStatus,
    ) : PiEvent

    data class Notice(
        val notice: PiNotice,
    ) : PiEvent
}

/** `shot` and `shot_update` (server.py:3260, 3398, 3426); `pending`/`enrichment` aren't read. */
@Serializable
private data class ShotPayload(
    val shot: ShotDetail,
    val stats: SessionStats? = null,
)

@Serializable
private data class ProcessingPayload(
    val state: ShotProcessingState,
)

@Serializable
private data class SwingSpeedPayload(
    val event: SwingSpeedReading,
    val stats: SessionStats? = null,
)

@Serializable
private data class DebugPayload(
    val enabled: Boolean = false,
    @SerialName("log_path") val logPath: String? = null,
)

@Serializable
private data class ErrorPayload(
    val error: String = "",
)

@Serializable
private data class SimSendFailedPayload(
    val target: String = "",
    val reason: String = "",
)

@Serializable
private data class ReasonPayload(
    val reason: String = "",
)

@Serializable
private data class MessagePayload(
    val message: String = "",
)

/**
 * Maps a [SocketEvent] to a [PiEvent]. Returns `null` for events this repository doesn't track
 * (e.g. `iwr6843_orientation_calibrated`), for unknown names, and for a malformed `club_changed`
 * (ignored like the Expo app does). Throws [IllegalArgumentException] for any other known event
 * with a malformed payload.
 */
internal fun decodePiEvent(event: SocketEvent): PiEvent? {
    val data = event.data ?: JsonNull
    LENIENT_DECODERS[event.name]?.let { return it(data) }
    return PI_EVENT_DECODERS[event.name]?.invoke(data)
}

private fun <T> JsonElement.decode(strategy: DeserializationStrategy<T>): T =
    PiJson.decodeFromJsonElement(strategy, this)

/** One decoder per server event name (server.py `socketio.emit(...)`). */
private val PI_EVENT_DECODERS: Map<String, (JsonElement) -> PiEvent> =
    mapOf(
        "shot" to { data -> data.decode(ShotPayload.serializer()).let { PiEvent.Shot(it.shot, it.stats) } },
        "shot_update" to
            { data -> data.decode(ShotPayload.serializer()).let { PiEvent.ShotUpdate(it.shot, it.stats) } },
        "shot_processing" to { data -> PiEvent.Processing(data.decode(ProcessingPayload.serializer()).state) },
        "swing_speed" to
            { data -> data.decode(SwingSpeedPayload.serializer()).let { PiEvent.SwingSpeed(it.event, it.stats) } },
        "session_state" to { data -> PiEvent.Session(data.decode(SessionState.serializer())) },
        "profiles" to { data -> PiEvent.Profiles(data.decode(ProfilesSnapshot.serializer())) },
        "power_status" to { data -> PiEvent.Power(data.decode(PowerStatus.serializer())) },
        "training_implement_changed" to { data ->
            PiEvent.TrainingImplementChanged(data.decode(TrainingImplement.serializer()))
        },
        "trigger_status" to { data -> PiEvent.Trigger(data.decode(TriggerStatus.serializer())) },
        "trigger_diagnostic" to { data -> PiEvent.Diagnostic(data.decode(TriggerDiagnostic.serializer())) },
        "camera_capture_settings" to
            { data -> PiEvent.CameraSettings(data.decode(CameraCaptureSettings.serializer())) },
        "sim_status" to { data -> PiEvent.Sim(data.decode(SimStatus.serializer())) },
        "sim_shot" to { data -> PiEvent.SimShotSent(data.decode(SimShot.serializer())) },
        "sim_player" to { data -> PiEvent.SimPlayerChanged(data.decode(SimPlayer.serializer())) },
        "radar_config" to { data -> PiEvent.Radar(data.decode(RadarConfig.serializer())) },
        "debug_toggled" to ::decodeDebug,
        "debug_status" to ::decodeDebug,
        "debug_reading" to { data -> PiEvent.DebugReadingReceived(data.decode(DebugReading.serializer())) },
        "debug_shot" to { data -> PiEvent.DebugShotReceived(data.decode(DebugShotLog.serializer())) },
        "cloud_upload_status" to { data -> PiEvent.Cloud(data.decode(CloudUploadStatus.serializer())) },
        "radar_config_error" to
            { data -> notice(PiNotice.RadarConfigFailed(data.decode(ErrorPayload.serializer()).error)) },
        "camera_capture_settings_error" to
            { data -> notice(PiNotice.CameraSettingsFailed(data.decode(ErrorPayload.serializer()).error)) },
        "training_implement_error" to { data ->
            notice(PiNotice.TrainingImplementFailed(data.decode(ErrorPayload.serializer()).error))
        },
        "sim_send_failed" to { data ->
            data.decode(SimSendFailedPayload.serializer()).let { notice(PiNotice.SimSendFailed(it.target, it.reason)) }
        },
        "sim_shot_dropped" to
            { data -> notice(PiNotice.SimShotDropped(data.decode(ReasonPayload.serializer()).reason)) },
        "shutdown_ack" to { data -> notice(PiNotice.ShuttingDown(data.decode(MessagePayload.serializer()).message)) },
    )

/**
 * Events that never fail to decode: their malformed variants are part of the contract (Expo
 * `socket.test.ts`), so each maps them to a defined outcome instead of a dropped event.
 */
private val LENIENT_DECODERS: Map<String, (JsonElement) -> PiEvent?> =
    mapOf(
        "session_cleared" to { data -> PiEvent.Cleared(decodeSessionCleared(data)) },
        // A malformed club change is ignored (Expo: "ignores a malformed club change").
        "club_changed" to { data -> data.stringField("club")?.let(PiEvent::ClubChanged) },
        // A malformed refusal still fails the pending deletion, with a reason of our own.
        "delete_shot_error" to
            { data -> PiEvent.DeleteShotFailed(data.stringField("error")?.takeIf { it.isNotBlank() }) },
    )

/**
 * `session_cleared {profile_id, shots}` (server.py:1937). An older server sent no payload; a list
 * that doesn't decode is treated like a missing one (`shots == null`), so the caller re-syncs
 * instead of guessing which rows went.
 */
private fun decodeSessionCleared(data: JsonElement): SessionCleared {
    val shots =
        ((data as? JsonObject)?.get("shots") as? JsonArray)?.let { list ->
            runCatching { list.decode(ListSerializer(ShotDetail.serializer())) }.getOrNull()
        }
    return SessionCleared(profileId = data.stringField("profile_id"), shots = shots)
}

/** The value of [key] when this is an object holding a JSON **string** there; otherwise `null`. */
private fun JsonElement.stringField(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun decodeDebug(data: JsonElement): PiEvent =
    data.decode(DebugPayload.serializer()).let {
        PiEvent.Debug(it.enabled, it.logPath)
    }

private fun notice(notice: PiNotice): PiEvent = PiEvent.Notice(notice)
