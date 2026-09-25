// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.socketio.SocketEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Socket.IO frames for the Pi's session API.
 *
 * **Captured** frames were recorded verbatim from `openflight-server --mock` at backend main
 * `2974e5d` on 2026-09-25 (one client, Engine.IO polling): connect, the on-connect requests,
 * two `simulate_shot`s around an `add_profile`, a `set_club`, debug on/off, the error replies,
 * a `clear_session` of the second profile and a confirmed `delete_shot`.
 *
 * **HAND-BUILT** frames are events the mock never emits (`shot_update`, `shot_processing`,
 * `power_status`) or that need a mode the capture didn't run; each cites the server source it
 * follows.
 */
internal object PiFixtures {
    /** Shot #1's timestamp (default profile), the `delete_shot` key. */
    const val SHOT_TIMESTAMP = "2026-09-25T10:03:35.906612"

    /** Shot #2's timestamp (filed under Sam). */
    const val SECOND_SHOT_TIMESTAMP = "2026-09-25T10:05:15.938602"

    const val DEFAULT_PROFILE_ID = "1a1b2ef343ba4c81b172a20afb76ef6d"
    const val SAM_PROFILE_ID = "cb266c3984d14b38944c92b14bd0d6c2"

    /** On connect: the roster (server.py:1781 `_emit_profiles`). */
    const val CONNECT_PROFILES_FRAME: String =
        """42["profiles",{"profiles":[{"id":"1a1b2ef343ba4c81b172a20afb76ef6d","name":"Profile 1","created_""" +
            """at":"2026-09-25T14:00:21Z","settings":{}}],"active_profile_id":"1a1b2ef343ba4c81b172a20afb76ef6d""" +
            """"}]"""

    /** On connect: `session_state` with `mock_mode`/`debug_mode` (server.py:1786). */
    const val CONNECT_SESSION_STATE_FRAME: String =
        """42["session_state",{"stats":{"shot_count":0,"avg_ball_speed":0,"max_ball_speed":0,"min_ball_spee""" +
            """d":0,"avg_club_speed":null,"avg_smash_factor":null,"avg_carry_est":0,"avg_spin_rpm":null,"spin_d""" +
            """etection_rate":0,"mode":"mock"},"shots":[],"club":"driver","mock_mode":true,"debug_mode":false}]"""

    /** `trigger_status` (server.py:1787, 1799). */
    const val TRIGGER_STATUS_FRAME: String =
        """42["trigger_status",{"mode":"mock","trigger_type":null,"radar_connected":false,"radar_port":null""" +
            ""","triggers_total":0,"triggers_accepted":0,"triggers_rejected":0}]"""

    /** `get_radar_config` (server.py:2017). */
    const val RADAR_CONFIG_FRAME: String =
        """42["radar_config",{"min_speed":10,"max_speed":220,"min_magnitude":0,"transmit_power":0}]"""

    /** `get_debug_status` (server.py:1996). */
    const val DEBUG_STATUS_FRAME: String =
        """42["debug_status",{"enabled":false,"log_path":null}]"""

    /** `get_camera_capture_settings` without `--camera-capture` (server.py:1471). */
    const val CAMERA_CAPTURE_SETTINGS_FRAME: String =
        """42["camera_capture_settings",{"enabled":false,"available":false,"alignment_x_pct":50.0,"alignmen""" +
            """t_y_pct":50.0}]"""

    /** `simulate_shot` → `shot` #1, filed under the default profile (server.py:3260). */
    const val SHOT_FRAME: String =
        """42["shot",{"shot":{"shot_number":1,"ball_speed_mph":116.2,"ball_speed_raw_mph":null,"club_speed_""" +
            """mph":78.6,"smash_factor":1.48,"estimated_carry_yards":179,"carry_range":[171,188],"club":"driver""" +
            """","profile_id":"1a1b2ef343ba4c81b172a20afb76ef6d","profile_name":"Profile 1","timestamp":"2026-0""" +
            """9-25T10:03:35.906612","impact_timestamp":null,"peak_magnitude":null,"launch_angle_vertical":10.7""" +
            ""","launch_angle_horizontal":2.4,"launch_angle_confidence":0.66,"launch_angle_vertical_confidence"""" +
            """:0.66,"launch_angle_horizontal_confidence":0.66,"launch_angle_vertical_source":"mock","launch_an""" +
            """gle_horizontal_source":"mock","angle_source":"mock","club_angle_deg":-1.9,"club_path_deg":4.6,"e""" +
            """xperimental_attack_angle_deg":null,"experimental_attack_angle_status":null,"experimental_club_pa""" +
            """th_deg":null,"experimental_club_path_status":null,"experimental_fused_attack_angle_deg":null,"ex""" +
            """perimental_fused_club_path_deg":null,"experimental_fused_status":null,"experimental_fused_attack""" +
            """_angle_confidence":null,"experimental_fused_club_path_confidence":null,"experimental_camera_trac""" +
            """e_deg":null,"experimental_aoa_offset_source":null,"iwr6843_horizontal_deg":null,"iwr6843_horizon""" +
            """tal_confidence":null,"experimental_camera_horizontal_deg":null,"experimental_camera_horizontal_c""" +
            """onfidence":null,"experimental_camera_horizontal_status":null,"experimental_camera_iwr_delta_deg"""" +
            """:null,"camera_replay":null,"spin_axis_deg":3.6,"inclinometer":null,"spin_rpm":2593,"spin_rpm_mea""" +
            """sured":null,"spin_source":null,"spin_method":null,"spin_confidence":0.7,"spin_quality":"high","s""" +
            """pin_multipath_fade_hz":null,"spin_snr":null,"spin_modulation_depth":null,"spin_peak_freq_hz":nul""" +
            """l,"spin_candidate_rpm":null,"spin_seam_cycles":null,"spin_at_lower_rail":null,"spin_at_upper_rai""" +
            """l":null,"spin_candidates":null,"spin_phase_method":null,"spin_phase_rpm":null,"spin_phase_snr":n""" +
            """ull,"spin_phase_agreement_pct":null,"spin_phase_confirmed":false,"spin_rejection_reason":null,"c""" +
            """arry_spin_adjusted":null},"stats":{"shot_count":1,"avg_ball_speed":116.18442296964207,"max_ball_""" +
            """speed":116.18442296964207,"min_ball_speed":116.18442296964207,"avg_club_speed":78.59683464227986""" +
            ""","avg_smash_factor":1.4782328512138654,"avg_carry_est":179.49272086955148,"avg_spin_rpm":2592.55""" +
            """2990850858,"spin_detection_rate":1.0,"mode":"mock","std_dev":0}}]"""

    /** `add_profile {name: "  Sam  "}`: trimmed, appended and made active (profiles.py:134). */
    const val PROFILES_AFTER_ADD_FRAME: String =
        """42["profiles",{"profiles":[{"id":"1a1b2ef343ba4c81b172a20afb76ef6d","name":"Profile 1","created_""" +
            """at":"2026-09-25T14:00:21Z","settings":{}},{"id":"cb266c3984d14b38944c92b14bd0d6c2","name":"Sam",""" +
            """"created_at":"2026-09-25T14:04:00Z","settings":{}}],"active_profile_id":"cb266c3984d14b38944c92b""" +
            """14bd0d6c2"}]"""

    /** `set_club {club: "7-iron"}` (server.py:1810). */
    const val CLUB_CHANGED_FRAME: String =
        """42["club_changed",{"club":"7-iron"}]"""

    /** `shot` #2, filed under Sam. */
    const val SECOND_SHOT_FRAME: String =
        """42["shot",{"shot":{"shot_number":2,"ball_speed_mph":92.7,"ball_speed_raw_mph":null,"club_speed_m""" +
            """ph":73.8,"smash_factor":1.26,"estimated_carry_yards":123,"carry_range":[117,129],"club":"7-iron"""" +
            ""","profile_id":"cb266c3984d14b38944c92b14bd0d6c2","profile_name":"Sam","timestamp":"2026-09-25T10""" +
            """:05:15.938602","impact_timestamp":null,"peak_magnitude":null,"launch_angle_vertical":23.4,"launc""" +
            """h_angle_horizontal":-3.0,"launch_angle_confidence":0.66,"launch_angle_vertical_confidence":0.66,""" +
            """"launch_angle_horizontal_confidence":0.66,"launch_angle_vertical_source":"mock","launch_angle_ho""" +
            """rizontal_source":"mock","angle_source":"mock","club_angle_deg":-0.3,"club_path_deg":1.2,"experim""" +
            """ental_attack_angle_deg":null,"experimental_attack_angle_status":null,"experimental_club_path_deg""" +
            """":null,"experimental_club_path_status":null,"experimental_fused_attack_angle_deg":null,"experime""" +
            """ntal_fused_club_path_deg":null,"experimental_fused_status":null,"experimental_fused_attack_angle""" +
            """_confidence":null,"experimental_fused_club_path_confidence":null,"experimental_camera_trace_deg"""" +
            """:null,"experimental_aoa_offset_source":null,"iwr6843_horizontal_deg":null,"iwr6843_horizontal_co""" +
            """nfidence":null,"experimental_camera_horizontal_deg":null,"experimental_camera_horizontal_confide""" +
            """nce":null,"experimental_camera_horizontal_status":null,"experimental_camera_iwr_delta_deg":null,""" +
            """"camera_replay":null,"spin_axis_deg":-6.8,"inclinometer":null,"spin_rpm":5768,"spin_rpm_measured""" +
            """":null,"spin_source":null,"spin_method":null,"spin_confidence":0.3,"spin_quality":"low","spin_mu""" +
            """ltipath_fade_hz":null,"spin_snr":null,"spin_modulation_depth":null,"spin_peak_freq_hz":null,"spi""" +
            """n_candidate_rpm":null,"spin_seam_cycles":null,"spin_at_lower_rail":null,"spin_at_upper_rail":nul""" +
            """l,"spin_candidates":null,"spin_phase_method":null,"spin_phase_rpm":null,"spin_phase_snr":null,"s""" +
            """pin_phase_agreement_pct":null,"spin_phase_confirmed":false,"spin_rejection_reason":null,"carry_s""" +
            """pin_adjusted":null},"stats":{"shot_count":2,"avg_ball_speed":104.45247725939382,"max_ball_speed"""" +
            """:116.18442296964207,"min_ball_speed":92.72053154914558,"avg_club_speed":76.19958894993891,"avg_s""" +
            """mash_factor":1.3672843634833496,"avg_carry_est":151.36082188819472,"avg_spin_rpm":4180.293852911""" +
            """912,"spin_detection_rate":1.0,"mode":"mock","std_dev":16.591476736457924}}]"""

    /** `toggle_debug` on (server.py:1985). */
    const val DEBUG_TOGGLED_ON_FRAME: String =
        """42["debug_toggled",{"enabled":true,"log_path":"/Users/btripp/openflight_logs/debug_20260925_1006""" +
            """05.jsonl"}]"""

    /** `toggle_debug` off: no `log_path` key (server.py:1989). */
    const val DEBUG_TOGGLED_OFF_FRAME: String =
        """42["debug_toggled",{"enabled":false}]"""

    /** `delete_shot` of an unknown timestamp (server.py:1963). */
    const val DELETE_SHOT_ERROR_FRAME: String =
        """42["delete_shot_error",{"error":"Shot not found"}]"""

    /** `set_radar_config` on `--mock` (server.py:2031). */
    const val RADAR_CONFIG_ERROR_FRAME: String =
        """42["radar_config_error",{"error":"Radar not connected"}]"""

    /** `set_camera_capture_settings` without a camera (server.py:1478). */
    const val CAMERA_CAPTURE_SETTINGS_ERROR_FRAME: String =
        """42["camera_capture_settings_error",{"error":"High-speed camera capture is not running"}]"""

    /** `set_training_implement` with an unknown key (server.py:1874). */
    const val TRAINING_IMPLEMENT_ERROR_FRAME: String =
        """42["training_implement_error",{"error":"Unknown training implement"}]"""

    /** `set_training_implement {implement: "stack-100g"}` (server.py:1879). */
    const val TRAINING_IMPLEMENT_CHANGED_FRAME: String =
        """42["training_implement_changed",{"implement":"stack-100g","label":"Stack 100g"}]"""

    /** `clear_session` for Sam: shot #1 (the default profile's) remains (server.py:1937). */
    const val SESSION_CLEARED_FRAME: String =
        """42["session_cleared",{"profile_id":"cb266c3984d14b38944c92b14bd0d6c2","shots":[{"shot_number":1,""" +
            """"ball_speed_mph":116.2,"ball_speed_raw_mph":null,"club_speed_mph":78.6,"smash_factor":1.48,"esti""" +
            """mated_carry_yards":179,"carry_range":[171,188],"club":"driver","profile_id":"1a1b2ef343ba4c81b17""" +
            """2a20afb76ef6d","profile_name":"Profile 1","timestamp":"2026-09-25T10:03:35.906612","impact_times""" +
            """tamp":null,"peak_magnitude":null,"launch_angle_vertical":10.7,"launch_angle_horizontal":2.4,"lau""" +
            """nch_angle_confidence":0.66,"launch_angle_vertical_confidence":0.66,"launch_angle_horizontal_conf""" +
            """idence":0.66,"launch_angle_vertical_source":"mock","launch_angle_horizontal_source":"mock","angl""" +
            """e_source":"mock","club_angle_deg":-1.9,"club_path_deg":4.6,"experimental_attack_angle_deg":null,""" +
            """"experimental_attack_angle_status":null,"experimental_club_path_deg":null,"experimental_club_pat""" +
            """h_status":null,"experimental_fused_attack_angle_deg":null,"experimental_fused_club_path_deg":nul""" +
            """l,"experimental_fused_status":null,"experimental_fused_attack_angle_confidence":null,"experiment""" +
            """al_fused_club_path_confidence":null,"experimental_camera_trace_deg":null,"experimental_aoa_offse""" +
            """t_source":null,"iwr6843_horizontal_deg":null,"iwr6843_horizontal_confidence":null,"experimental_""" +
            """camera_horizontal_deg":null,"experimental_camera_horizontal_confidence":null,"experimental_camer""" +
            """a_horizontal_status":null,"experimental_camera_iwr_delta_deg":null,"camera_replay":null,"spin_ax""" +
            """is_deg":3.6,"inclinometer":null,"spin_rpm":2593,"spin_rpm_measured":null,"spin_source":null,"spi""" +
            """n_method":null,"spin_confidence":0.7,"spin_quality":"high","spin_multipath_fade_hz":null,"spin_s""" +
            """nr":null,"spin_modulation_depth":null,"spin_peak_freq_hz":null,"spin_candidate_rpm":null,"spin_s""" +
            """eam_cycles":null,"spin_at_lower_rail":null,"spin_at_upper_rail":null,"spin_candidates":null,"spi""" +
            """n_phase_method":null,"spin_phase_rpm":null,"spin_phase_snr":null,"spin_phase_agreement_pct":null""" +
            ""","spin_phase_confirmed":false,"spin_rejection_reason":null,"carry_spin_adjusted":null}]}]"""

    /** `get_session` after that clear. */
    const val SESSION_STATE_AFTER_CLEAR_FRAME: String =
        """42["session_state",{"stats":{"shot_count":1,"avg_ball_speed":116.18442296964207,"max_ball_speed"""" +
            """:116.18442296964207,"min_ball_speed":116.18442296964207,"avg_club_speed":78.59683464227986,"avg_""" +
            """smash_factor":1.4782328512138654,"avg_carry_est":179.49272086955148,"avg_spin_rpm":2592.55299085""" +
            """0858,"spin_detection_rate":1.0,"mode":"mock","std_dev":0},"shots":[{"shot_number":1,"ball_speed_""" +
            """mph":116.2,"ball_speed_raw_mph":null,"club_speed_mph":78.6,"smash_factor":1.48,"estimated_carry_""" +
            """yards":179,"carry_range":[171,188],"club":"driver","profile_id":"1a1b2ef343ba4c81b172a20afb76ef6""" +
            """d","profile_name":"Profile 1","timestamp":"2026-09-25T10:03:35.906612","impact_timestamp":null,"""" +
            """peak_magnitude":null,"launch_angle_vertical":10.7,"launch_angle_horizontal":2.4,"launch_angle_co""" +
            """nfidence":0.66,"launch_angle_vertical_confidence":0.66,"launch_angle_horizontal_confidence":0.66""" +
            ""","launch_angle_vertical_source":"mock","launch_angle_horizontal_source":"mock","angle_source":"m""" +
            """ock","club_angle_deg":-1.9,"club_path_deg":4.6,"experimental_attack_angle_deg":null,"experimenta""" +
            """l_attack_angle_status":null,"experimental_club_path_deg":null,"experimental_club_path_status":nu""" +
            """ll,"experimental_fused_attack_angle_deg":null,"experimental_fused_club_path_deg":null,"experimen""" +
            """tal_fused_status":null,"experimental_fused_attack_angle_confidence":null,"experimental_fused_clu""" +
            """b_path_confidence":null,"experimental_camera_trace_deg":null,"experimental_aoa_offset_source":nu""" +
            """ll,"iwr6843_horizontal_deg":null,"iwr6843_horizontal_confidence":null,"experimental_camera_horiz""" +
            """ontal_deg":null,"experimental_camera_horizontal_confidence":null,"experimental_camera_horizontal""" +
            """_status":null,"experimental_camera_iwr_delta_deg":null,"camera_replay":null,"spin_axis_deg":3.6,""" +
            """"inclinometer":null,"spin_rpm":2593,"spin_rpm_measured":null,"spin_source":null,"spin_method":nu""" +
            """ll,"spin_confidence":0.7,"spin_quality":"high","spin_multipath_fade_hz":null,"spin_snr":null,"sp""" +
            """in_modulation_depth":null,"spin_peak_freq_hz":null,"spin_candidate_rpm":null,"spin_seam_cycles":""" +
            """null,"spin_at_lower_rail":null,"spin_at_upper_rail":null,"spin_candidates":null,"spin_phase_meth""" +
            """od":null,"spin_phase_rpm":null,"spin_phase_snr":null,"spin_phase_agreement_pct":null,"spin_phase""" +
            """_confirmed":false,"spin_rejection_reason":null,"carry_spin_adjusted":null}],"club":"7-iron"}]"""

    /** `delete_shot` of shot #1's timestamp → `session_state` without it (server.py:1966). */
    const val SESSION_STATE_AFTER_DELETE_FRAME: String =
        """42["session_state",{"stats":{"shot_count":0,"avg_ball_speed":0,"max_ball_speed":0,"min_ball_spee""" +
            """d":0,"avg_club_speed":null,"avg_smash_factor":null,"avg_carry_est":0,"avg_spin_rpm":null,"spin_d""" +
            """etection_rate":0,"mode":"mock"},"shots":[],"club":"7-iron"}]"""

    /**
     * HAND-BUILT: shot #1's enriched `shot_update` {shot, stats} (server.py:3260 with
     * emit_event="shot_update", server.py:246); only the two iwr6843 fields differ from [SHOT_FRAME].
     */
    const val SHOT_UPDATE_FRAME: String =
        """42["shot_update",{"shot":{"shot_number":1,"ball_speed_mph":116.2,"ball_speed_raw_mph":null,"club""" +
            """_speed_mph":78.6,"smash_factor":1.48,"estimated_carry_yards":179,"carry_range":[171,188],"club":""" +
            """"driver","profile_id":"1a1b2ef343ba4c81b172a20afb76ef6d","profile_name":"Profile 1","timestamp":""" +
            """"2026-09-25T10:03:35.906612","impact_timestamp":null,"peak_magnitude":null,"launch_angle_vertica""" +
            """l":10.7,"launch_angle_horizontal":2.4,"launch_angle_confidence":0.66,"launch_angle_vertical_conf""" +
            """idence":0.66,"launch_angle_horizontal_confidence":0.66,"launch_angle_vertical_source":"mock","la""" +
            """unch_angle_horizontal_source":"mock","angle_source":"mock","club_angle_deg":-1.9,"club_path_deg"""" +
            """:4.6,"experimental_attack_angle_deg":null,"experimental_attack_angle_status":null,"experimental_""" +
            """club_path_deg":null,"experimental_club_path_status":null,"experimental_fused_attack_angle_deg":n""" +
            """ull,"experimental_fused_club_path_deg":null,"experimental_fused_status":null,"experimental_fused""" +
            """_attack_angle_confidence":null,"experimental_fused_club_path_confidence":null,"experimental_came""" +
            """ra_trace_deg":null,"experimental_aoa_offset_source":null,"iwr6843_horizontal_deg":2.1,"iwr6843_h""" +
            """orizontal_confidence":0.8,"experimental_camera_horizontal_deg":null,"experimental_camera_horizon""" +
            """tal_confidence":null,"experimental_camera_horizontal_status":null,"experimental_camera_iwr_delta""" +
            """_deg":null,"camera_replay":null,"spin_axis_deg":3.6,"inclinometer":null,"spin_rpm":2593,"spin_rp""" +
            """m_measured":null,"spin_source":null,"spin_method":null,"spin_confidence":0.7,"spin_quality":"hig""" +
            """h","spin_multipath_fade_hz":null,"spin_snr":null,"spin_modulation_depth":null,"spin_peak_freq_hz""" +
            """":null,"spin_candidate_rpm":null,"spin_seam_cycles":null,"spin_at_lower_rail":null,"spin_at_uppe""" +
            """r_rail":null,"spin_candidates":null,"spin_phase_method":null,"spin_phase_rpm":null,"spin_phase_s""" +
            """nr":null,"spin_phase_agreement_pct":null,"spin_phase_confirmed":false,"spin_rejection_reason":nu""" +
            """ll,"carry_spin_adjusted":null},"stats":{"shot_count":1,"avg_ball_speed":116.18442296964207,"max_""" +
            """ball_speed":116.18442296964207,"min_ball_speed":116.18442296964207,"avg_club_speed":78.596834642""" +
            """27986,"avg_smash_factor":1.4782328512138654,"avg_carry_est":179.49272086955148,"avg_spin_rpm":25""" +
            """92.552990850858,"spin_detection_rate":1.0,"mode":"mock","std_dev":0}}]"""

    /** HAND-BUILT: shot #1 re-sent unchanged with `enrichment.status = "skipped"` (server.py:3418-3437). */
    const val SHOT_UPDATE_SKIPPED_FRAME: String =
        """42["shot_update",{"shot":{"shot_number":1,"ball_speed_mph":116.2,"ball_speed_raw_mph":null,"club""" +
            """_speed_mph":78.6,"smash_factor":1.48,"estimated_carry_yards":179,"carry_range":[171,188],"club":""" +
            """"driver","profile_id":"1a1b2ef343ba4c81b172a20afb76ef6d","profile_name":"Profile 1","timestamp":""" +
            """"2026-09-25T10:03:35.906612","impact_timestamp":null,"peak_magnitude":null,"launch_angle_vertica""" +
            """l":10.7,"launch_angle_horizontal":2.4,"launch_angle_confidence":0.66,"launch_angle_vertical_conf""" +
            """idence":0.66,"launch_angle_horizontal_confidence":0.66,"launch_angle_vertical_source":"mock","la""" +
            """unch_angle_horizontal_source":"mock","angle_source":"mock","club_angle_deg":-1.9,"club_path_deg"""" +
            """:4.6,"experimental_attack_angle_deg":null,"experimental_attack_angle_status":null,"experimental_""" +
            """club_path_deg":null,"experimental_club_path_status":null,"experimental_fused_attack_angle_deg":n""" +
            """ull,"experimental_fused_club_path_deg":null,"experimental_fused_status":null,"experimental_fused""" +
            """_attack_angle_confidence":null,"experimental_fused_club_path_confidence":null,"experimental_came""" +
            """ra_trace_deg":null,"experimental_aoa_offset_source":null,"iwr6843_horizontal_deg":null,"iwr6843_""" +
            """horizontal_confidence":null,"experimental_camera_horizontal_deg":null,"experimental_camera_horiz""" +
            """ontal_confidence":null,"experimental_camera_horizontal_status":null,"experimental_camera_iwr_del""" +
            """ta_deg":null,"camera_replay":null,"spin_axis_deg":3.6,"inclinometer":null,"spin_rpm":2593,"spin_""" +
            """rpm_measured":null,"spin_source":null,"spin_method":null,"spin_confidence":0.7,"spin_quality":"h""" +
            """igh","spin_multipath_fade_hz":null,"spin_snr":null,"spin_modulation_depth":null,"spin_peak_freq_""" +
            """hz":null,"spin_candidate_rpm":null,"spin_seam_cycles":null,"spin_at_lower_rail":null,"spin_at_up""" +
            """per_rail":null,"spin_candidates":null,"spin_phase_method":null,"spin_phase_rpm":null,"spin_phase""" +
            """_snr":null,"spin_phase_agreement_pct":null,"spin_phase_confirmed":false,"spin_rejection_reason":""" +
            """null,"carry_spin_adjusted":null},"stats":{"shot_count":1,"avg_ball_speed":116.18442296964207,"ma""" +
            """x_ball_speed":116.18442296964207,"min_ball_speed":116.18442296964207,"avg_club_speed":78.5968346""" +
            """4227986,"avg_smash_factor":1.4782328512138654,"avg_carry_est":179.49272086955148,"avg_spin_rpm":""" +
            """2592.552990850858,"spin_detection_rate":1.0,"mode":"mock","std_dev":0},"pending":{},"enrichment"""" +
            """:{"status":"skipped","reason":"enrichment queue full","hardware":["iwr6843"]}}]"""

    /** HAND-BUILT: `shot_processing` (server.py:2111-2113; states from rolling_buffer/monitor.py:445-704). */
    const val SHOT_PROCESSING_FRAME: String =
        """42["shot_processing",{"state":"calculating"}]"""

    /**
     * HAND-BUILT: `PowerStatus.to_dict()` (power/models.py:30-47), values from the Expo
     * `socket.test.ts` device fixture.
     */
    const val POWER_STATUS_FRAME: String =
        """42["power_status",{"available":true,"provider":"geekworm","state":"on_battery","battery_percent"""" +
            """:78.0,"battery_voltage_v":3.91,"external_power":false,"updated_at":"2026-09-22T05:30:00Z","error""" +
            """":null}]"""

    /**
     * HAND-BUILT: a swing-speed rep. Captured from `--mock-swing-speed` at reference `b053194`
     * (2026-09-24), with `player_name` replaced by main's `profile_id`/`profile_name`
     * (`swing_speed_to_dict`, server.py:3588-3603).
     */
    const val SWING_SPEED_FRAME =
        """42["swing_speed",{"event":{"peak_speed_mph":97.4,"timestamp":"2026-09-24T16:00:00.000001",""" +
            """"duration_ms":1200,"reading_count":5,"trigger_speed_mph":80.1,"peak_magnitude":210.5,""" +
            """"training_implement":"stack-100g","training_implement_label":"Stack 100g",""" +
            """"profile_id":"1a1b2ef343ba4c81b172a20afb76ef6d","profile_name":"Profile 1",""" +
            """"unit":"mph","mode":"swing-speed"},"stats":{"shot_count":1,"avg_ball_speed":97.4,""" +
            """"max_ball_speed":97.4,"min_ball_speed":97.4,"std_dev":0,"avg_club_speed":97.4,""" +
            """"avg_smash_factor":null,"avg_carry_est":0}}]"""

    /** Parses a `42[...]` frame into the [SocketEvent] the client would publish. */
    fun event(frame: String): SocketEvent {
        val array = Json.parseToJsonElement(frame.removePrefix("42")) as JsonArray
        return SocketEvent(
            name =
                array
                    .first()
                    .toString()
                    .trim('"'),
            args = array.drop(1),
        )
    }
}
