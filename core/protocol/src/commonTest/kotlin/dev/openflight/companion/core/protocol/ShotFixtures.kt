// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

/**
 * The shared contract fixture from `ios/OpenFlightTests/Fixtures/shot_v1.json`, embedded as a
 * string constant (see the matching copy and rationale in `core:model`'s test fixtures).
 */
internal const val SHOT_V1_FIXTURE_JSON = """
{
  "ball_speed_mph": 151.4,
  "club": "driver",
  "club_path_deg": 2.1,
  "club_speed_mph": 103.2,
  "estimated_carry_yards": 264,
  "event_id": "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
  "launch_angle_horizontal": -1.3,
  "launch_angle_vertical": 12.6,
  "schema_version": 1,
  "smash_factor": 1.47,
  "spin_axis_deg": -3.4,
  "spin_rpm": 2380,
  "timestamp": "2026-07-29T19:42:10.123456"
}
"""

internal fun shotJson(
    eventId: String,
    schemaVersion: Int = 1,
): String =
    """
    {
      "schema_version": $schemaVersion,
      "event_id": "$eventId",
      "timestamp": "2026-08-05T23:54:00",
      "club": "driver",
      "ball_speed_mph": 140.0,
      "estimated_carry_yards": 250.0
    }
    """.trimIndent()
