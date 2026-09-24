// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One session row as the Pi's Socket.IO API sends it: the full `shot_to_dict` payload
 * (server.py ~893), or `swing_speed_to_shot_dict` in swing-speed mode (server.py ~3075).
 *
 * Every field except [timestamp] is optional, because the two builders differ and a future
 * server may drop fields; unknown keys are ignored when decoding.
 *
 * [timestamp] is the join key with the SSE/BLE [ShotEvent][dev.openflight.companion.core.model.ShotEvent]:
 * `build_shot_event` copies `shot_to_dict(...)["timestamp"]` verbatim, while its `event_id` is a
 * fresh UUID that never appears here. It is also the key `delete_shot` takes.
 */
@Serializable
data class ShotDetail(
    val timestamp: String,
    @SerialName("ball_speed_mph") val ballSpeedMph: Double? = null,
    @SerialName("ball_speed_raw_mph") val ballSpeedRawMph: Double? = null,
    @SerialName("club_speed_mph") val clubSpeedMph: Double? = null,
    @SerialName("smash_factor") val smashFactor: Double? = null,
    @SerialName("estimated_carry_yards") val estimatedCarryYards: Double? = null,
    /** `[low, high]` carry estimate in yards. */
    @SerialName("carry_range") val carryRange: List<Double>? = null,
    val club: String? = null,
    @SerialName("player_name") val playerName: String? = null,
    @SerialName("peak_magnitude") val peakMagnitude: Double? = null,
    @SerialName("launch_angle_vertical") val launchAngleVertical: Double? = null,
    @SerialName("launch_angle_horizontal") val launchAngleHorizontal: Double? = null,
    @SerialName("launch_angle_confidence") val launchAngleConfidence: Double? = null,
    @SerialName("launch_angle_vertical_confidence") val launchAngleVerticalConfidence: Double? = null,
    @SerialName("launch_angle_horizontal_confidence") val launchAngleHorizontalConfidence: Double? = null,
    @SerialName("launch_angle_vertical_source") val launchAngleVerticalSource: String? = null,
    @SerialName("launch_angle_horizontal_source") val launchAngleHorizontalSource: String? = null,
    /** `radar`, `camera`, `estimated` or `mock`. */
    @SerialName("angle_source") val angleSource: String? = null,
    @SerialName("club_angle_deg") val clubAngleDeg: Double? = null,
    @SerialName("club_path_deg") val clubPathDeg: Double? = null,
    @SerialName("spin_axis_deg") val spinAxisDeg: Double? = null,
    /** Inclinometer snapshot; its shape depends on the hardware, so it stays raw JSON. */
    val inclinometer: JsonElement? = null,
    @SerialName("spin_rpm") val spinRpm: Double? = null,
    @SerialName("spin_rpm_measured") val spinRpmMeasured: Double? = null,
    /** `measured` or `calculated`. */
    @SerialName("spin_source") val spinSource: String? = null,
    @SerialName("spin_method") val spinMethod: String? = null,
    @SerialName("spin_confidence") val spinConfidence: Double? = null,
    /** `high`, `medium`, `low` or `experimental`. */
    @SerialName("spin_quality") val spinQuality: String? = null,
    @SerialName("spin_multipath_fade_hz") val spinMultipathFadeHz: Double? = null,
    @SerialName("spin_snr") val spinSnr: Double? = null,
    @SerialName("spin_modulation_depth") val spinModulationDepth: Double? = null,
    @SerialName("spin_peak_freq_hz") val spinPeakFreqHz: Double? = null,
    @SerialName("spin_candidate_rpm") val spinCandidateRpm: Double? = null,
    @SerialName("spin_seam_cycles") val spinSeamCycles: Double? = null,
    @SerialName("spin_at_lower_rail") val spinAtLowerRail: Boolean? = null,
    @SerialName("spin_at_upper_rail") val spinAtUpperRail: Boolean? = null,
    /** Raw candidate list from the spin estimator; shape varies, so it stays raw JSON. */
    @SerialName("spin_candidates") val spinCandidates: JsonElement? = null,
    @SerialName("spin_phase_method") val spinPhaseMethod: String? = null,
    @SerialName("spin_phase_rpm") val spinPhaseRpm: Double? = null,
    @SerialName("spin_phase_snr") val spinPhaseSnr: Double? = null,
    @SerialName("spin_phase_agreement_pct") val spinPhaseAgreementPct: Double? = null,
    @SerialName("spin_phase_confirmed") val spinPhaseConfirmed: Boolean? = null,
    @SerialName("spin_rejection_reason") val spinRejectionReason: String? = null,
    @SerialName("carry_spin_adjusted") val carrySpinAdjusted: Double? = null,
    /** `rolling-buffer`, `mock` or `swing-speed`; absent on regular shot rows. */
    val mode: String? = null,
    @SerialName("swing_speed_duration_ms") val swingSpeedDurationMs: Double? = null,
    @SerialName("swing_speed_reading_count") val swingSpeedReadingCount: Int? = null,
    @SerialName("swing_speed_trigger_mph") val swingSpeedTriggerMph: Double? = null,
    @SerialName("training_implement") val trainingImplement: String? = null,
    @SerialName("training_implement_label") val trainingImplementLabel: String? = null,
) {
    /** Mirrors the web UI's `isSwingSpeedShot`. */
    val isSwingSpeed: Boolean get() = mode == "swing-speed" || club == "Swing Speed"

    /** Low end of [carryRange], when present. */
    val carryRangeLow: Double? get() = carryRange?.getOrNull(0)

    /** High end of [carryRange], when present. */
    val carryRangeHigh: Double? get() = carryRange?.getOrNull(1)
}
