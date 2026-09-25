// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-computed session statistics (`monitor.get_session_stats()`), sent with `shot`,
 * `swing_speed` and `session_state`. With no shots the server sends zeros and `null` averages.
 * In swing-speed mode the "ball speed" fields hold peak swing speeds.
 */
@Serializable
data class SessionStats(
    @SerialName("shot_count") val shotCount: Int = 0,
    @SerialName("avg_ball_speed") val avgBallSpeed: Double? = null,
    @SerialName("max_ball_speed") val maxBallSpeed: Double? = null,
    @SerialName("min_ball_speed") val minBallSpeed: Double? = null,
    @SerialName("std_dev") val stdDev: Double? = null,
    @SerialName("avg_club_speed") val avgClubSpeed: Double? = null,
    @SerialName("avg_smash_factor") val avgSmashFactor: Double? = null,
    @SerialName("avg_carry_est") val avgCarryEst: Double? = null,
    @SerialName("avg_spin_rpm") val avgSpinRpm: Double? = null,
    @SerialName("spin_detection_rate") val spinDetectionRate: Double? = null,
    val mode: String? = null,
) {
    companion object {
        /** What the server reports for an empty session. */
        val EMPTY: SessionStats =
            SessionStats(shotCount = 0, avgBallSpeed = 0.0, maxBallSpeed = 0.0, minBallSpeed = 0.0, avgCarryEst = 0.0)
    }
}

/**
 * `session_state` (server.py:1662 `_session_state_payload`): sent on connect, on `get_session` and
 * after `delete_shot`. [shots] is the whole session for **every profile**, oldest first; clients
 * filter by [ShotDetail.profileId]. [stats] also covers every profile. [mockMode] and [debugMode]
 * are present only in the on-connect variant.
 */
@Serializable
data class SessionState(
    val stats: SessionStats = SessionStats(),
    val shots: List<ShotDetail> = emptyList(),
    /** The club the Pi files shots under (`_current_club_id`); absent on older servers. */
    val club: String? = null,
    @SerialName("mock_mode") val mockMode: Boolean? = null,
    @SerialName("debug_mode") val debugMode: Boolean? = null,
)

/**
 * `session_cleared` (server.py:1931 `handle_clear_session`), broadcast after any client's
 * `clear_session {profile_id}`.
 *
 * @property profileId the profile whose rows were removed; `null` when the payload lacked it.
 * @property shots the whole session that **remains** (every other profile's rows), oldest first,
 *   or `null` when the payload carried no usable list (an older server sent no payload at all).
 */
data class SessionCleared(
    val profileId: String?,
    val shots: List<ShotDetail>?,
)

/** A swing-speed training rep (`swing_speed_to_dict`, the `event` of a `swing_speed` event). */
@Serializable
data class SwingSpeedReading(
    @SerialName("peak_speed_mph") val peakSpeedMph: Double,
    val timestamp: String,
    @SerialName("duration_ms") val durationMs: Double? = null,
    @SerialName("reading_count") val readingCount: Int? = null,
    @SerialName("trigger_speed_mph") val triggerSpeedMph: Double? = null,
    @SerialName("peak_magnitude") val peakMagnitude: Double? = null,
    @SerialName("training_implement") val trainingImplement: String? = null,
    @SerialName("training_implement_label") val trainingImplementLabel: String? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    val unit: String? = null,
    val mode: String? = null,
)

/**
 * `training_implement_changed`. [implement] is one of the server's `TRAINING_IMPLEMENT_LABELS`
 * keys ([TrainingImplement.KNOWN]); the server rejects any other with `training_implement_error`.
 */
@Serializable
data class TrainingImplement(
    val implement: String,
    val label: String,
) {
    companion object {
        /** server.py `TRAINING_IMPLEMENT_LABELS` (implement → label), in declaration order (b053194). */
        val KNOWN: Map<String, String> =
            linkedMapOf(
                "driver" to "Driver",
                "superspeed-light" to "SuperSpeed Light",
                "superspeed-medium" to "SuperSpeed Medium",
                "superspeed-heavy" to "SuperSpeed Heavy",
                "speed-stick-light" to "SuperSpeed Light",
                "speed-stick-medium" to "SuperSpeed Medium",
                "speed-stick-heavy" to "SuperSpeed Heavy",
                "stack" to "Stack",
                "stack-0g" to "Stack 0g",
                "stack-60g" to "Stack 60g",
                "stack-100g" to "Stack 100g",
                "stack-120g" to "Stack 120g",
                "stack-160g" to "Stack 160g",
                "stack-180g" to "Stack 180g",
                "stack-200g" to "Stack 200g",
                "stack-220g" to "Stack 220g",
                "stack-240g" to "Stack 240g",
                "stack-260g" to "Stack 260g",
                "stack-280g" to "Stack 280g",
                "stack-300g" to "Stack 300g",
                "rypstick" to "Rypstick",
                "rypstick-0w" to "Rypstick 0 Weights",
                "rypstick-1w" to "Rypstick 1 Weight",
                "rypstick-2w" to "Rypstick 2 Weights",
                "rypstick-3w" to "Rypstick 3 Weights",
                "rypstick-3w-cw" to "Rypstick 3 Weights + Counterweight",
                "custom" to "Custom",
            )
    }
}
