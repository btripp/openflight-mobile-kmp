// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `trigger_status` (server.py `_get_trigger_status`). */
@Serializable
data class TriggerStatus(
    /** `rolling-buffer`, `mock` or `swing-speed`. */
    val mode: String = "",
    @SerialName("trigger_type") val triggerType: String? = null,
    @SerialName("radar_connected") val radarConnected: Boolean = false,
    @SerialName("radar_port") val radarPort: String? = null,
    @SerialName("triggers_total") val triggersTotal: Int = 0,
    @SerialName("triggers_accepted") val triggersAccepted: Int = 0,
    @SerialName("triggers_rejected") val triggersRejected: Int = 0,
)

/** `trigger_diagnostic`: one rolling-buffer trigger, accepted or not (web `TriggerDiagnostic`). */
@Serializable
data class TriggerDiagnostic(
    val timestamp: String? = null,
    @SerialName("trigger_type") val triggerType: String? = null,
    val accepted: Boolean = false,
    val reason: String? = null,
    @SerialName("response_bytes") val responseBytes: Int? = null,
    @SerialName("total_readings") val totalReadings: Int? = null,
    @SerialName("outbound_readings") val outboundReadings: Int? = null,
    @SerialName("inbound_readings") val inboundReadings: Int? = null,
    @SerialName("peak_outbound_mph") val peakOutboundMph: Double? = null,
    @SerialName("peak_inbound_mph") val peakInboundMph: Double? = null,
    @SerialName("all_outbound_speeds") val allOutboundSpeeds: List<Double>? = null,
    @SerialName("all_inbound_speeds") val allInboundSpeeds: List<Double>? = null,
    @SerialName("peak_outbound_magnitude") val peakOutboundMagnitude: Double? = null,
    @SerialName("peak_inbound_magnitude") val peakInboundMagnitude: Double? = null,
    @SerialName("latency_ms") val latencyMs: Double? = null,
    @SerialName("ball_speed_mph") val ballSpeedMph: Double? = null,
    @SerialName("club_speed_mph") val clubSpeedMph: Double? = null,
    @SerialName("spin_rpm") val spinRpm: Double? = null,
    @SerialName("carry_yards") val carryYards: Double? = null,
)

/**
 * The camera's state, merged from `camera_status`, `ball_detection` and the on-connect
 * `session_state` (the server sends partial updates; the web UI merges them the same way).
 * [error] is the latest `camera_status` error, e.g. "Camera not initialized"; `null` otherwise.
 */
data class CameraStatus(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val streaming: Boolean = false,
    val ballDetected: Boolean = false,
    val ballConfidence: Double = 0.0,
    val error: String? = null,
)

/** `ball_detection`: the camera tracker's detection changed. */
@Serializable
data class BallDetection(
    val detected: Boolean = false,
    val confidence: Double = 0.0,
)

/**
 * `radar_config` (server.py `radar_config`): speed filters in mph, the magnitude filter, and
 * transmit power (0 = max, 7 = min).
 */
@Serializable
data class RadarConfig(
    @SerialName("min_speed") val minSpeed: Int = 0,
    @SerialName("max_speed") val maxSpeed: Int = 0,
    @SerialName("min_magnitude") val minMagnitude: Int = 0,
    @SerialName("transmit_power") val transmitPower: Int = 0,
)

/**
 * A partial `set_radar_config`: only non-null fields are sent. `maxSpeed = 0` clears the ceiling;
 * `transmitPower` outside 0..7 is ignored by the server.
 */
data class RadarConfigUpdate(
    val minSpeed: Int? = null,
    val maxSpeed: Int? = null,
    val minMagnitude: Int? = null,
    val transmitPower: Int? = null,
)

/** `debug_reading`: one raw radar reading, sent only while debug mode is on. */
@Serializable
data class DebugReading(
    val speed: Double,
    /** `inbound`, `outbound` or `unknown`. */
    val direction: String? = null,
    val magnitude: Double? = null,
    val timestamp: String? = null,
    /** `true` for readings shot detection ignores (anything not outbound). */
    val filtered: Boolean? = null,
)

/** `debug_shot`: the debug log entry for a shot, sent only while debug mode is on. */
@Serializable
data class DebugShotLog(
    val type: String? = null,
    val timestamp: String? = null,
    val radar: DebugShotRadar? = null,
    /** Camera tracking data, or `null`; the shape depends on the camera pipeline. */
    val camera: JsonElement? = null,
    val club: String? = null,
)

@Serializable
data class DebugShotRadar(
    @SerialName("ball_speed_mph") val ballSpeedMph: Double? = null,
    @SerialName("club_speed_mph") val clubSpeedMph: Double? = null,
    @SerialName("smash_factor") val smashFactor: Double? = null,
    @SerialName("peak_magnitude") val peakMagnitude: Double? = null,
)

/**
 * Debug mode and its live feeds. The lists are capped like the web UI's debug store (50 readings,
 * 20 shot logs, 50 diagnostics), oldest first.
 */
data class DebugState(
    val enabled: Boolean = false,
    /**
     * `false` until the Pi reports its debug mode (on-connect `session_state`, `debug_status` or
     * `debug_toggled`). Debug mode is server-global, so a recording may already be running: a
     * screen must not offer "Start" before this is `true` (Expo `useDeviceStore.debugLoaded`).
     */
    val loaded: Boolean = false,
    /** The server-side JSONL log file while debug mode is on. */
    val logPath: String? = null,
    val readings: List<DebugReading> = emptyList(),
    val shotLogs: List<DebugShotLog> = emptyList(),
    val triggerDiagnostics: List<TriggerDiagnostic> = emptyList(),
) {
    companion object {
        const val MAX_READINGS: Int = 50
        const val MAX_SHOT_LOGS: Int = 20
        const val MAX_TRIGGER_DIAGNOSTICS: Int = 50
    }
}
