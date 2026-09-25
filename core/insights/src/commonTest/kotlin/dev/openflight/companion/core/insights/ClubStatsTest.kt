// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.model.ShotEvent
import kotlin.test.Test

class ClubStatsTest {
    @Test
    fun emptyShotsProduceEmptyStats() {
        assertThat(computeClubStats(emptyList())).isEqualTo(ClubStats.EMPTY)
    }

    @Test
    fun averagesAndMaxIgnoreNoShots() {
        val shots =
            listOf(
                shot(ballSpeedMph = 140.0, carryYards = 240.0),
                shot(ballSpeedMph = 160.0, carryYards = 260.0),
            )

        val stats = computeClubStats(shots)

        assertThat(stats.shotCount).isEqualTo(2)
        assertThat(stats.avgBallSpeedMph).isEqualTo(150.0)
        assertThat(stats.maxBallSpeedMph).isEqualTo(160.0)
        assertThat(stats.avgCarryYards).isEqualTo(250.0)
    }

    @Test
    fun theMinimumAndTheSampleSpreadCoverTheBallSpeeds() {
        // Expo sessionStats.test.ts: 100/110/120 → a sample std dev of 10.
        val stats =
            computeClubStats(listOf(shot(ballSpeedMph = 100.0), shot(ballSpeedMph = 110.0), shot(ballSpeedMph = 120.0)))

        assertThat(stats.minBallSpeedMph).isEqualTo(100.0)
        assertThat(stats.stdDevBallSpeedMph).isEqualTo(10.0)
        assertThat(computeClubStats(listOf(shot())).stdDevBallSpeedMph).isEqualTo(0.0)
    }

    @Test
    fun clubSpeedAndSmashFactorIgnoreNullShots() {
        val shots =
            listOf(
                shot(clubSpeedMph = 100.0, smashFactor = 1.4),
                shot(clubSpeedMph = null, smashFactor = null),
                shot(clubSpeedMph = 120.0, smashFactor = 1.6),
            )

        val stats = computeClubStats(shots)

        assertThat(stats.avgClubSpeedMph).isEqualTo(110.0)
        assertThat(stats.avgSmashFactor).isEqualTo(1.5)
    }

    @Test
    fun clubSpeedAndSmashFactorAreNullWhenNoShotReportsThem() {
        val shots = listOf(shot(clubSpeedMph = null, smashFactor = null))

        val stats = computeClubStats(shots)

        assertThat(stats.avgClubSpeedMph).isNull()
        assertThat(stats.avgSmashFactor).isNull()
    }

    @Test
    fun clubChipsCountShotsPerClubInFirstAppearanceOrder() {
        val shots =
            listOf(
                shot(club = "driver"),
                shot(club = "7-iron"),
                shot(club = "driver"),
                shot(club = "pw"),
                shot(club = "7-iron"),
            )

        val chips = computeClubChips(shots)

        assertThat(chips).containsExactly(
            ClubChip("driver", 2),
            ClubChip("7-iron", 2),
            ClubChip("pw", 1),
        )
    }
}

private fun shot(
    club: String = "driver",
    ballSpeedMph: Double = 140.0,
    carryYards: Double = 240.0,
    clubSpeedMph: Double? = null,
    smashFactor: Double? = null,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = "00000000-0000-4000-8000-000000000000",
        timestamp = "2026-08-05T23:54:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = clubSpeedMph,
        smashFactor = smashFactor,
        estimatedCarryYards = carryYards,
    )
