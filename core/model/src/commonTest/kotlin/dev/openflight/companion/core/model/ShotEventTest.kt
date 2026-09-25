// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ShotEventTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesSharedV1Fixture() {
        val shot = json.decodeFromString(ShotEvent.serializer(), SHOT_V1_FIXTURE_JSON)

        assertThat(shot.schemaVersion).isEqualTo(1)
        assertThat(shot.eventId).isEqualTo("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1")
        assertThat(shot.ballSpeedMph).isEqualTo(151.4)
        assertThat(shot.estimatedCarryYards).isEqualTo(264.0)
        assertThat(shot.spinRpm).isEqualTo(2380.0)
        assertThat(shot.displayClub).isEqualTo("Driver")
    }

    @Test
    fun decodesExplicitNullMeasurements() {
        val data =
            """
            {
              "schema_version": 1,
              "event_id": "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
              "timestamp": "2026-07-29T19:42:10",
              "club": "iron_7",
              "ball_speed_mph": 100.0,
              "club_speed_mph": null,
              "smash_factor": null,
              "estimated_carry_yards": 142,
              "launch_angle_vertical": null,
              "launch_angle_horizontal": null,
              "spin_rpm": null,
              "club_path_deg": null,
              "spin_axis_deg": null
            }
            """.trimIndent()

        val shot = json.decodeFromString(ShotEvent.serializer(), data)

        assertThat(shot.clubSpeedMph).isNull()
        assertThat(shot.spinRpm).isNull()
        assertThat(shot.displayClub).isEqualTo("Iron 7")
    }

    @Test
    fun ignoresUnknownExtraKeys() {
        val data =
            """
            {
              "schema_version": 1,
              "event_id": "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
              "timestamp": "2026-07-29T19:42:10",
              "club": "driver",
              "ball_speed_mph": 100.0,
              "estimated_carry_yards": 142,
              "spin_candidates": [1, 2, 3]
            }
            """.trimIndent()

        val shot = json.decodeFromString(ShotEvent.serializer(), data)

        assertThat(shot.club).isEqualTo("driver")
    }

    @Test
    fun displayClubReplacesUnderscoresAndCapitalizesEachWord() {
        assertThat(makeShotEvent(club = "7-iron").displayClub).isEqualTo("7-Iron")
        assertThat(makeShotEvent(club = "3-wood").displayClub).isEqualTo("3-Wood")
        assertThat(makeShotEvent(club = "driver").displayClub).isEqualTo("Driver")
    }

    @Test
    fun displayClubUsesTheWedgeNamesNotTheWireAbbreviation() {
        // Manual testing showed "Pw" on the latest-shot card (plan R8f).
        assertThat(makeShotEvent(club = "pw").displayClub).isEqualTo("Pitching Wedge")
        assertThat(makeShotEvent(club = "gw").displayClub).isEqualTo("Gap Wedge")
        assertThat(makeShotEvent(club = "sw").displayClub).isEqualTo("Sand Wedge")
        assertThat(makeShotEvent(club = "lw").displayClub).isEqualTo("Lob Wedge")
    }

    @Test
    fun everyKnownClubShowsItsDisplayName() {
        GolfClub.entries.forEach { club ->
            assertThat(makeShotEvent(club = club.wireValue).displayClub).isEqualTo(club.displayName)
        }
        assertThat(GolfClub.displayNameFor("mystery_club")).isEqualTo("Mystery Club")
    }
}
