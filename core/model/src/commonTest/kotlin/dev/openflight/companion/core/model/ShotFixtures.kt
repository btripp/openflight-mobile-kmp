// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * The shared contract fixture from `ios/OpenFlightTests/Fixtures/shot_v1.json`, embedded as a
 * string constant. KMP commonTest resource loading is awkward on iOS simulator targets, so this
 * mirrors the reference file byte-for-byte instead of reading it from disk.
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

internal fun makeShotEvent(
    eventId: String = "11111111-2222-3333-4444-555555555555",
    ballSpeedMph: Double = 140.0,
    club: String = "driver",
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = eventId,
        timestamp = "2026-08-05T23:54:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = 106.1,
        smashFactor = 1.45,
        estimatedCarryYards = 270.0,
        launchAngleVertical = 14.2,
        launchAngleHorizontal = -0.3,
        spinRpm = 2512.0,
        clubPathDeg = -1.5,
        spinAxisDeg = 0.4,
    )
