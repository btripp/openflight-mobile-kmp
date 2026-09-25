// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Session aggregation over the Pi's rows ([computeDetailStats]), ported case for case from the
 * Expo app's `sessionStats.test.ts` (which mirrors the kiosk's `computeStats`), plus the
 * active-profile filter from `stats.tsx` (`feat/stats-tab`).
 */
class SessionStatsTest {
    @Test
    fun anEmptySessionIsZeroRatherThanNoSession() {
        // The kiosk's contract: counters read zero; only the two optional measurements are null.
        assertThat(computeDetailStats(emptyList())).isEqualTo(
            ClubStats(
                shotCount = 0,
                avgBallSpeedMph = 0.0,
                maxBallSpeedMph = 0.0,
                avgCarryYards = 0.0,
                avgClubSpeedMph = null,
                avgSmashFactor = null,
                minBallSpeedMph = 0.0,
                stdDevBallSpeedMph = 0.0,
            ),
        )
    }

    @Test
    fun aSingleShotDescribesItselfWithNoSpread() {
        val stats = computeDetailStats(listOf(row(ballSpeedMph = 148.2, carry = 266.0, club = 104.1, smash = 1.42)))

        assertThat(stats.shotCount).isEqualTo(1)
        assertThat(stats.avgBallSpeedMph).isCloseTo(148.2, TOLERANCE)
        assertThat(stats.maxBallSpeedMph).isCloseTo(148.2, TOLERANCE)
        assertThat(stats.minBallSpeedMph).isCloseTo(148.2, TOLERANCE)
        assertThat(stats.avgCarryYards).isEqualTo(266.0)
        assertThat(stats.avgClubSpeedMph!!).isCloseTo(104.1, TOLERANCE)
        assertThat(stats.avgSmashFactor!!).isCloseTo(1.42, TOLERANCE)
        // One shot has no spread; dividing by n - 1 would divide by zero.
        assertThat(stats.stdDevBallSpeedMph).isEqualTo(0.0)
    }

    @Test
    fun severalShotsAreAveragedBoundedAndSpread() {
        val stats =
            computeDetailStats(
                listOf(
                    row(ballSpeedMph = 100.0, carry = 200.0),
                    row(ballSpeedMph = 110.0, carry = 220.0),
                    row(ballSpeedMph = 120.0, carry = 240.0),
                ),
            )

        assertThat(stats.shotCount).isEqualTo(3)
        assertThat(stats.avgBallSpeedMph).isEqualTo(110.0)
        assertThat(stats.maxBallSpeedMph).isEqualTo(120.0)
        assertThat(stats.minBallSpeedMph).isEqualTo(100.0)
        assertThat(stats.avgCarryYards).isEqualTo(220.0)
        // Sample standard deviation: sqrt((100 + 0 + 100) / 2) = 10.
        assertThat(stats.stdDevBallSpeedMph).isEqualTo(10.0)
    }

    @Test
    fun theSpreadIsTheSampleNotThePopulation() {
        // Two shots 10 apart: the population figure would be 5, the sample figure sqrt(50).
        val stats = computeDetailStats(listOf(row(ballSpeedMph = 140.0), row(ballSpeedMph = 150.0)))

        assertThat(stats.stdDevBallSpeedMph).isCloseTo(sqrt(50.0), TOLERANCE)
    }

    @Test
    fun noClubSpeedAtAllWhenNoShotMeasuredOne() {
        val stats = computeDetailStats(listOf(row(club = null), row(club = null)))

        // Null, not zero: nothing was measured, which is not a slow swing.
        assertThat(stats.avgClubSpeedMph).isNull()
        assertThat(stats.shotCount).isEqualTo(2)
    }

    @Test
    fun clubSpeedIsAveragedOverOnlyTheShotsThatMeasuredOne() {
        val stats = computeDetailStats(listOf(row(club = null), row(club = 100.0), row(club = 110.0)))

        // 105, not 70: the unmeasured shot doesn't count as a zero.
        assertThat(stats.avgClubSpeedMph).isEqualTo(105.0)
    }

    @Test
    fun smashFactorIsAveragedOverOnlyTheShotsThatHaveOne() {
        val stats = computeDetailStats(listOf(row(smash = 1.4), row(smash = null), row(smash = 1.5)))

        assertThat(stats.avgSmashFactor!!).isCloseTo(1.45, TOLERANCE)
    }

    @Test
    fun noSmashFactorAtAllWhenNoShotHasOne() {
        val stats = computeDetailStats(listOf(row(smash = null), row(smash = null)))

        assertThat(stats.avgSmashFactor).isNull()
    }

    @Test
    fun ballSpeedAndCarryStayWholeWhenTheOptionalMeasurementsAreMissing() {
        // A shot that arrived before enrichment still counts toward the figures it does carry.
        val stats =
            computeDetailStats(
                listOf(
                    row(ballSpeedMph = 140.0, carry = 250.0, club = null, smash = null),
                    row(ballSpeedMph = 150.0, carry = 260.0, club = 104.0, smash = 1.44),
                ),
            )

        assertThat(stats.avgBallSpeedMph).isEqualTo(145.0)
        assertThat(stats.avgCarryYards).isEqualTo(255.0)
        assertThat(stats.avgClubSpeedMph).isEqualTo(104.0)
        assertThat(stats.avgSmashFactor!!).isCloseTo(1.44, TOLERANCE)
    }

    @Test
    fun theSseStatsUseTheSameSpread() {
        assertThat(sampleStdDev(listOf(100.0, 110.0, 120.0))).isEqualTo(10.0)
        assertThat(sampleStdDev(listOf(140.0))).isEqualTo(0.0)
        assertThat(sampleStdDev(emptyList())).isEqualTo(0.0)
    }

    @Test
    fun onlyTheActiveProfilesRowsAreKept() {
        val alexLate = row(timestamp = "t3", profileId = "alex")
        val sam = row(timestamp = "t2", profileId = "sam")
        val alexEarly = row(timestamp = "t1", profileId = "alex")
        val session = listOf(alexLate, sam, alexEarly)

        assertThat(session.forProfile("alex")).containsExactly(alexLate, alexEarly)
        assertThat(session.forProfile("sam")).containsExactly(sam)
        // No profile known yet: nothing, rather than a guess at whose shots these are.
        assertThat(session.forProfile("")).isEmpty()
        assertThat(session.forProfile("nobody")).isEmpty()
    }

    private fun row(
        ballSpeedMph: Double = 148.2,
        carry: Double = 266.0,
        club: Double? = 104.1,
        smash: Double? = 1.42,
        timestamp: String = "2026-09-14T10:00:00",
        profileId: String? = null,
    ): ShotDetail =
        ShotDetail(
            timestamp = timestamp,
            ballSpeedMph = ballSpeedMph,
            clubSpeedMph = club,
            smashFactor = smash,
            estimatedCarryYards = carry,
            profileId = profileId,
        )

    private companion object {
        const val TOLERANCE = 1e-10
    }
}
