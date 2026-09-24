// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.socketio.SocketEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Socket.IO frames captured from `openflight-server --mock` (reference b053194) on 2026-09-24. */
internal object PiFixtures {
    const val SHOT_TIMESTAMP = "2026-09-24T15:38:33.264795"

    const val SHOT_FRAME =
        """42["shot",{"shot":{"ball_speed_mph":143.3,"ball_speed_raw_mph":null,"club_speed_mph":98.3,""" +
            """"smash_factor":1.46,"estimated_carry_yards":243,"carry_range":[231,255],"club":"driver",""" +
            """"player_name":"Player 1","timestamp":"2026-09-24T15:38:33.264795","peak_magnitude":null,""" +
            """"launch_angle_vertical":15.4,"launch_angle_horizontal":0.9,"launch_angle_confidence":0.72,""" +
            """"launch_angle_vertical_confidence":0.72,"launch_angle_horizontal_confidence":0.72,""" +
            """"launch_angle_vertical_source":"mock","launch_angle_horizontal_source":"mock","angle_source":"mock",""" +
            """"club_angle_deg":-6.6,"club_path_deg":-2.0,"spin_axis_deg":-0.3,"inclinometer":null,"spin_rpm":2836,""" +
            """"spin_rpm_measured":null,"spin_source":null,"spin_method":null,"spin_confidence":0.6,""" +
            """"spin_quality":"medium","spin_multipath_fade_hz":null,"spin_snr":null,"spin_modulation_depth":null,""" +
            """"spin_peak_freq_hz":null,"spin_candidate_rpm":null,"spin_seam_cycles":null,""" +
            """"spin_at_lower_rail":null,""" +
            """"spin_at_upper_rail":null,"spin_candidates":null,"spin_phase_method":null,"spin_phase_rpm":null,""" +
            """"spin_phase_snr":null,"spin_phase_agreement_pct":null,"spin_phase_confirmed":false,""" +
            """"spin_rejection_reason":null,"carry_spin_adjusted":null},"stats":{"shot_count":1,""" +
            """"avg_ball_speed":143.28745757452205,"max_ball_speed":143.28745757452205,""" +
            """"min_ball_speed":143.28745757452205,"std_dev":0,"avg_club_speed":98.27610134234705,""" +
            """"avg_smash_factor":1.4580091763650342,"avg_carry_est":243.30227105757902}}]"""

    const val CONNECT_SESSION_STATE_FRAME =
        """42["session_state",{"stats":{"shot_count":0,"avg_ball_speed":0,"max_ball_speed":0,"min_ball_speed":0,""" +
            """"avg_club_speed":null,"avg_smash_factor":null,"avg_carry_est":0},"shots":[],"mock_mode":true,""" +
            """"debug_mode":false,"camera_available":false,"camera_enabled":false,"camera_streaming":false,""" +
            """"ball_detected":false,"player_name":"Player 1"}]"""

    const val TRIGGER_STATUS_FRAME =
        """42["trigger_status",{"mode":"mock","trigger_type":null,"radar_connected":false,"radar_port":null,""" +
            """"triggers_total":0,"triggers_accepted":0,"triggers_rejected":0}]"""

    /** A swing-speed rep (`swing_speed_to_dict`) as `--mock-swing-speed` sends it. */
    const val SWING_SPEED_FRAME =
        """42["swing_speed",{"event":{"peak_speed_mph":97.4,"timestamp":"2026-09-24T16:00:00.000001",""" +
            """"duration_ms":1200,"reading_count":5,"trigger_speed_mph":80.1,"peak_magnitude":210.5,""" +
            """"training_implement":"stack-100g","training_implement_label":"Stack 100g","player_name":"Ann",""" +
            """"unit":"mph","mode":"swing-speed"},"stats":{"shot_count":1,"avg_ball_speed":97.4,""" +
            """"max_ball_speed":97.4,"min_ball_speed":97.4,"std_dev":0,"avg_club_speed":97.4,""" +
            """"avg_smash_factor":null,"avg_carry_est":0}}]"""

    /** Parses a captured `42[...]` frame into the [SocketEvent] the client would publish. */
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
