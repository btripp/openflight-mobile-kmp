// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One session row as the Pi's Socket.IO API sends it: the full `shot_to_dict` payload
 * (backend `2974e5d` server.py:917, from `Shot.to_dict`, launch_monitor.py:372), or
 * `swing_speed_to_shot_dict` in swing-speed mode (server.py:3606).
 *
 * Every field except [timestamp] is optional, because the two builders differ and a future
 * server may drop fields; unknown keys are ignored when decoding. Blobs whose shape depends on
 * the hardware (`inclinometer`, `spin_candidates`) stay raw JSON.
 *
 * **`null` means "not measured", never "zero".** Plan §9.1 and the Expo payload mirror say eight
 * fields (`ball_speed_raw_mph`, `club_speed_mph`, `smash_factor`, `spin_rpm`,
 * `spin_rpm_measured`, `spin_confidence`, `spin_phase_rpm`, `carry_spin_adjusted`) are serialized
 * with `if value`, so a real 0 arrives as `null`. At backend `2974e5d` `shot_to_dict` rounds with
 * `is not None` (server.py:937-938), so a 0 does survive; only [smashFactor] is `null` whenever
 * the club speed is missing **or 0** (launch_monitor.py:312). Don't render a `null` as 0 and don't
 * rely on 0 never appearing.
 *
 * [timestamp] is the join key with the SSE/BLE [ShotEvent][dev.openflight.companion.core.model.ShotEvent]:
 * `build_shot_event` copies `shot_to_dict(...)["timestamp"]` verbatim, while its `event_id` is a
 * fresh UUID that never appears here. It is also the key `delete_shot` takes.
 */
@Serializable
data class ShotDetail(
    val timestamp: String,
    /**
     * The per-monitor-run sequence (launch_monitor.py:201). Stable across `shot` and its
     * `shot_update`, never reused after a delete, and absent on swing-speed rows.
     */
    @SerialName("shot_number") val shotNumber: Int? = null,
    @SerialName("ball_speed_mph") val ballSpeedMph: Double? = null,
    @SerialName("ball_speed_raw_mph") val ballSpeedRawMph: Double? = null,
    @SerialName("club_speed_mph") val clubSpeedMph: Double? = null,
    @SerialName("smash_factor") val smashFactor: Double? = null,
    @SerialName("estimated_carry_yards") val estimatedCarryYards: Double? = null,
    /** `[low, high]` carry estimate in yards. */
    @SerialName("carry_range") val carryRange: List<Double>? = null,
    val club: String? = null,
    /** The profile active when the shot was detected; `""` on rows from before profiles. */
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("profile_name") val profileName: String? = null,
    /** Epoch seconds aligned to the OPS trigger (not ISO like [timestamp]). */
    @SerialName("impact_timestamp") val impactTimestamp: Double? = null,
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
    @SerialName("experimental_attack_angle_deg") val experimentalAttackAngleDeg: Double? = null,
    @SerialName("experimental_attack_angle_status") val experimentalAttackAngleStatus: String? = null,
    @SerialName("experimental_club_path_deg") val experimentalClubPathDeg: Double? = null,
    @SerialName("experimental_club_path_status") val experimentalClubPathStatus: String? = null,
    @SerialName("experimental_fused_attack_angle_deg") val experimentalFusedAttackAngleDeg: Double? = null,
    @SerialName("experimental_fused_club_path_deg") val experimentalFusedClubPathDeg: Double? = null,
    @SerialName("experimental_fused_status") val experimentalFusedStatus: String? = null,
    /** A graded label, not a number (launch_monitor.py:259). */
    @SerialName("experimental_fused_attack_angle_confidence") val experimentalFusedAttackAngleConfidence: String? =
        null,
    /** A graded label, not a number (launch_monitor.py:260). */
    @SerialName("experimental_fused_club_path_confidence") val experimentalFusedClubPathConfidence: String? = null,
    @SerialName("experimental_camera_trace_deg") val experimentalCameraTraceDeg: Double? = null,
    @SerialName("experimental_aoa_offset_source") val experimentalAoaOffsetSource: String? = null,
    @SerialName("iwr6843_horizontal_deg") val iwr6843HorizontalDeg: Double? = null,
    @SerialName("iwr6843_horizontal_confidence") val iwr6843HorizontalConfidence: Double? = null,
    @SerialName("experimental_camera_horizontal_deg") val experimentalCameraHorizontalDeg: Double? = null,
    @SerialName("experimental_camera_horizontal_confidence") val experimentalCameraHorizontalConfidence: Double? =
        null,
    @SerialName("experimental_camera_horizontal_status") val experimentalCameraHorizontalStatus: String? = null,
    @SerialName("experimental_camera_iwr_delta_deg") val experimentalCameraIwrDeltaDeg: Double? = null,
    /** The high-speed camera capture matched to this shot, when there is one; see [CameraReplay]. */
    @SerialName("camera_replay") val cameraReplay: CameraReplay? = null,
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
