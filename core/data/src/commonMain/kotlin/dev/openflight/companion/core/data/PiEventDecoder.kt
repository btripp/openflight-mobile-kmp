// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.BallDetection
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugReading
import dev.openflight.companion.core.model.pi.DebugShotLog
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.SessionState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

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

    data class SwingSpeed(
        val reading: SwingSpeedReading,
        val stats: SessionStats?,
    ) : PiEvent

    data class Session(
        val state: SessionState,
    ) : PiEvent

    data object SessionCleared : PiEvent

    data class PlayerChanged(
        val playerName: String,
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

    data class Camera(
        val update: CameraStatusPayload,
    ) : PiEvent

    data class Ball(
        val detection: BallDetection,
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

/** A partial `camera_status`: every field is optional and only present fields are merged. */
@Serializable
internal data class CameraStatusPayload(
    val available: Boolean? = null,
    val enabled: Boolean? = null,
    val streaming: Boolean? = null,
    @SerialName("ball_detected") val ballDetected: Boolean? = null,
    @SerialName("ball_confidence") val ballConfidence: Double? = null,
    val error: String? = null,
)

@Serializable
private data class ShotPayload(
    val shot: ShotDetail,
    val stats: SessionStats? = null,
)

@Serializable
private data class SwingSpeedPayload(
    val event: SwingSpeedReading,
    val stats: SessionStats? = null,
)

@Serializable
private data class PlayerPayload(
    @SerialName("player_name") val playerName: String,
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
 * (`club_changed` belongs to `ShotRepository`, `iwr6843_orientation_calibrated` to calibration)
 * and for unknown names. Throws [IllegalArgumentException] for a known event with a malformed payload.
 */
internal fun decodePiEvent(event: SocketEvent): PiEvent? = PI_EVENT_DECODERS[event.name]?.invoke(event.data ?: JsonNull)

private fun <T> JsonElement.decode(strategy: DeserializationStrategy<T>): T =
    PiJson.decodeFromJsonElement(strategy, this)

/** One decoder per server event name (server.py `socketio.emit(...)`). */
private val PI_EVENT_DECODERS: Map<String, (JsonElement) -> PiEvent> =
    mapOf(
        "shot" to { data -> data.decode(ShotPayload.serializer()).let { PiEvent.Shot(it.shot, it.stats) } },
        "swing_speed" to
            { data -> data.decode(SwingSpeedPayload.serializer()).let { PiEvent.SwingSpeed(it.event, it.stats) } },
        "session_state" to { data -> PiEvent.Session(data.decode(SessionState.serializer())) },
        "session_cleared" to { _ -> PiEvent.SessionCleared },
        "player_changed" to { data -> PiEvent.PlayerChanged(data.decode(PlayerPayload.serializer()).playerName) },
        "training_implement_changed" to { data ->
            PiEvent.TrainingImplementChanged(data.decode(TrainingImplement.serializer()))
        },
        "trigger_status" to { data -> PiEvent.Trigger(data.decode(TriggerStatus.serializer())) },
        "trigger_diagnostic" to { data -> PiEvent.Diagnostic(data.decode(TriggerDiagnostic.serializer())) },
        "camera_status" to { data -> PiEvent.Camera(data.decode(CameraStatusPayload.serializer())) },
        "ball_detection" to { data -> PiEvent.Ball(data.decode(BallDetection.serializer())) },
        "sim_status" to { data -> PiEvent.Sim(data.decode(SimStatus.serializer())) },
        "sim_shot" to { data -> PiEvent.SimShotSent(data.decode(SimShot.serializer())) },
        "sim_player" to { data -> PiEvent.SimPlayerChanged(data.decode(SimPlayer.serializer())) },
        "radar_config" to { data -> PiEvent.Radar(data.decode(RadarConfig.serializer())) },
        "debug_toggled" to ::decodeDebug,
        "debug_status" to ::decodeDebug,
        "debug_reading" to { data -> PiEvent.DebugReadingReceived(data.decode(DebugReading.serializer())) },
        "debug_shot" to { data -> PiEvent.DebugShotReceived(data.decode(DebugShotLog.serializer())) },
        "cloud_upload_status" to { data -> PiEvent.Cloud(data.decode(CloudUploadStatus.serializer())) },
        "delete_shot_error" to
            { data -> notice(PiNotice.DeleteShotFailed(data.decode(ErrorPayload.serializer()).error)) },
        "radar_config_error" to
            { data -> notice(PiNotice.RadarConfigFailed(data.decode(ErrorPayload.serializer()).error)) },
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

private fun decodeDebug(data: JsonElement): PiEvent =
    data.decode(DebugPayload.serializer()).let {
        PiEvent.Debug(it.enabled, it.logPath)
    }

private fun notice(notice: PiNotice): PiEvent = PiEvent.Notice(notice)
