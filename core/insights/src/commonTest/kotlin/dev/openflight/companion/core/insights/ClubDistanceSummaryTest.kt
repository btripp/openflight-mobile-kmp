// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class ClubDistanceSummaryTest {
    @Test
    fun noShotsHaveNoSummary() {
        assertThat(summarizeClubDistances(emptyList())).isNull()
    }

    @Test
    fun threeCarriesGiveTheirMeanMedianAndSampleStdDev() {
        // Plan F5 fixture: 150/155/160 → mean 155, std dev 5.
        val summary = summarizeClubDistances(carries(150.0, 155.0, 160.0))!!

        assertThat(summary.shotCount).isEqualTo(3)
        assertThat(summary.meanCarryYards).isCloseTo(155.0, TOLERANCE)
        assertThat(summary.medianCarryYards).isCloseTo(155.0, TOLERANCE)
        assertThat(summary.stdDevCarryYards).isCloseTo(5.0, TOLERANCE)
        assertThat(summary.plusMinusYards).isEqualTo(5)
        assertThat(summary.excludedCount).isEqualTo(0)
    }

    @Test
    fun percentilesInterpolateBetweenTheClosestRanks() {
        // Sorted 150, 155, 160: rank (n − 1)·p = 0.2 → 151 and 1.8 → 159.
        val summary = summarizeClubDistances(carries(160.0, 150.0, 155.0))!!

        assertThat(summary.p10CarryYards).isCloseTo(151.0, TOLERANCE)
        assertThat(summary.p90CarryYards).isCloseTo(159.0, TOLERANCE)
    }

    @Test
    fun anEvenCountHasTheMiddlePairsMeanAsMedian() {
        val summary = summarizeClubDistances(carries(140.0, 150.0, 160.0, 190.0))!!

        assertThat(summary.medianCarryYards).isCloseTo(155.0, TOLERANCE)
    }

    @Test
    fun oneShotHasNoSpread() {
        val summary = summarizeClubDistances(carries(150.0))!!

        assertThat(summary.stdDevCarryYards).isCloseTo(0.0, TOLERANCE)
        assertThat(summary.p10CarryYards).isCloseTo(150.0, TOLERANCE)
        assertThat(summary.p90CarryYards).isCloseTo(150.0, TOLERANCE)
    }

    @Test
    fun theMeanTotalIsOverShotsWithAnEstimatedTotal() {
        val summary =
            summarizeClubDistances(
                listOf(
                    ClubDistanceSample(carryYards = 150.0, totalYards = 160.0),
                    ClubDistanceSample(carryYards = 160.0, totalYards = 172.0),
                    ClubDistanceSample(carryYards = 170.0, totalYards = null),
                ),
            )!!

        assertThat(summary.meanTotalYards).isNotNull().isCloseTo(166.0, TOLERANCE)
    }

    @Test
    fun withoutTotalsTheMeanTotalIsAbsentNotZero() {
        assertThat(summarizeClubDistances(carries(150.0, 160.0))!!.meanTotalYards).isNull()
    }

    @Test
    fun offlineStatsUseOnlyShotsWithAMeasuredSide() {
        val summary =
            summarizeClubDistances(
                listOf(
                    ClubDistanceSample(carryYards = 150.0, offlineYards = -4.0),
                    ClubDistanceSample(carryYards = 152.0, offlineYards = 6.0),
                    ClubDistanceSample(carryYards = 154.0, offlineYards = null),
                ),
            )!!

        assertThat(summary.meanOfflineYards).isNotNull().isCloseTo(1.0, TOLERANCE)
        // Sample std dev of −4 and 6: sqrt(50) ≈ 7.071.
        assertThat(summary.stdDevOfflineYards).isNotNull().isCloseTo(7.0711, 0.001)
    }

    @Test
    fun withoutAMeasuredSideThereAreNoOfflineStats() {
        val summary = summarizeClubDistances(carries(150.0, 160.0))!!

        assertThat(summary.meanOfflineYards).isNull()
        assertThat(summary.stdDevOfflineYards).isNull()
    }

    @Test
    fun aFarCarryIsLeftOutAsAPossibleBadReadAndCounted() {
        // The shared median/MAD rule (findDispersionOutliers), not a new z-score (plan A13).
        val samples = carries(148.0, 150.0, 152.0, 149.0, 151.0, 150.0, 153.0, 247.0)

        val summary = summarizeClubDistances(samples)!!

        assertThat(summary.excludedCount).isEqualTo(1)
        assertThat(summary.outlierIndexes).isEqualTo(setOf(7))
        assertThat(summary.shotCount).isEqualTo(7)
        assertThat(summary.meanCarryYards).isCloseTo(150.4286, 0.001)
    }

    @Test
    fun belowTheOutlierMinimumNothingIsLeftOut() {
        val summary = summarizeClubDistances(carries(150.0, 151.0, 149.0, 250.0))!!

        assertThat(summary.outlierIndexes).isEmpty()
        assertThat(summary.shotCount).isEqualTo(4)
    }

    private fun carries(vararg yards: Double) = yards.map { ClubDistanceSample(carryYards = it) }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
