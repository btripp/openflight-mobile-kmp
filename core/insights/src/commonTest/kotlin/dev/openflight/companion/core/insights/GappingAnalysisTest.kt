// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.openflight.companion.core.model.GolfClub
import kotlin.test.Test

class GappingAnalysisTest {
    @Test
    fun aWellGappedFourteenClubBagHasThirteenGapsAndNoInsights() {
        val analysis = analyzeGapping(STANDARD_BAG.map { (club, carry) -> measured(club, carry) })

        assertThat(analysis.gaps.map { it.gapYards }).containsExactly(
            20.0,
            15.0,
            15.0,
            10.0,
            10.0,
            10.0,
            10.0,
            10.0,
            10.0,
            10.0,
            12.0,
            13.0,
            15.0,
        )
        assertThat(analysis.gaps.first().longer).isEqualTo(GolfClub.DRIVER)
        assertThat(analysis.gaps.first().shorter).isEqualTo(GolfClub.WOOD_3)
        assertThat(analysis.gaps.map { it.flag }.filterNotNull()).isEmpty()
        assertThat(analysis.insights).isEmpty()
    }

    @Test
    fun twoClubsFiveYardsApartAreTooTight() {
        val analysis = analyzeGapping(listOf(measured(GolfClub.IRON_7, 160.0), measured(GolfClub.IRON_8, 155.0)))

        assertThat(analysis.gaps.single().flag).isEqualTo(GapFlag.TOO_TIGHT)
        val insight = analysis.insights.single() as GapInsight.TooTight
        assertThat(insight.longer).isEqualTo(GolfClub.IRON_7)
        assertThat(insight.shorter).isEqualTo(GolfClub.IRON_8)
        assertThat(insight.gapYards).isCloseTo(5.0, TOLERANCE)
    }

    @Test
    fun wideSpreadsOverlappingMoreThanHalfAreTooTightEvenTenYardsApart() {
        // σ = 12 each: bands 148–172 and 138–162 overlap 14 of 24 yards (58%).
        val analysis =
            analyzeGapping(
                listOf(
                    measured(GolfClub.IRON_7, 160.0, spread = 12.0),
                    measured(GolfClub.IRON_8, 150.0, spread = 12.0),
                ),
            )

        val insight = analysis.insights.single() as GapInsight.TooTight
        assertThat(insight.bandOverlap).isCloseTo(14.0 / 24.0, TOLERANCE)
    }

    @Test
    fun tenYardsApartWithTightSpreadsIsFine() {
        val analysis = analyzeGapping(listOf(measured(GolfClub.IRON_7, 160.0), measured(GolfClub.IRON_8, 150.0)))

        assertThat(analysis.insights).isEmpty()
    }

    @Test
    fun ironsMoreThanTwentyYardsApartAreTooWide() {
        val analysis = analyzeGapping(listOf(measured(GolfClub.IRON_5, 185.0), measured(GolfClub.IRON_7, 160.0)))

        val insight = analysis.insights.single() as GapInsight.TooWide
        assertThat(insight.gapYards).isCloseTo(25.0, TOLERANCE)
        assertThat(insight.limitYards).isEqualTo(GappingThresholds.MAX_GAP_IRONS_YARDS)
    }

    @Test
    fun woodsGetTheWiderThirtyYardLimit() {
        val fine = analyzeGapping(listOf(measured(GolfClub.DRIVER, 255.0), measured(GolfClub.WOOD_3, 230.0)))
        val wide = analyzeGapping(listOf(measured(GolfClub.DRIVER, 265.0), measured(GolfClub.WOOD_3, 230.0)))

        assertThat(fine.insights).isEmpty()
        val insight = wide.insights.single() as GapInsight.TooWide
        assertThat(insight.limitYards).isEqualTo(GappingThresholds.MAX_GAP_WOODS_YARDS)
    }

    @Test
    fun aHybridNextToAnIronUsesTheWoodLimit() {
        val analysis = analyzeGapping(listOf(measured(GolfClub.HYBRID_3, 205.0), measured(GolfClub.IRON_5, 180.0)))

        assertThat(analysis.insights).isEmpty()
    }

    @Test
    fun aLongerClubCarryingLessIsOutOfOrder() {
        val analysis = analyzeGapping(listOf(measured(GolfClub.IRON_6, 165.0), measured(GolfClub.IRON_7, 170.0)))

        assertThat(analysis.gaps.single().flag).isEqualTo(GapFlag.OUT_OF_ORDER)
        val insight = analysis.insights.single() as GapInsight.OutOfOrder
        assertThat(insight.longerCarryYards).isCloseTo(165.0, TOLERANCE)
        assertThat(insight.shorterCarryYards).isCloseTo(170.0, TOLERANCE)
    }

    @Test
    fun aClubWithTooFewShotsIsReportedAndItsNeighboursAreCompared() {
        val analysis =
            analyzeGapping(
                listOf(
                    measured(GolfClub.IRON_7, 160.0),
                    GappingClub(GolfClub.IRON_8, summarizeClubDistances(carries(150.0, 151.0, 149.0))),
                    GappingClub(GolfClub.IRON_9, null),
                    measured(GolfClub.PITCHING_WEDGE, 145.0),
                ),
            )

        assertThat(analysis.insights).containsExactly(
            GapInsight.InsufficientData(GolfClub.IRON_8, 3, GappingThresholds.MIN_SHOTS),
            GapInsight.InsufficientData(GolfClub.IRON_9, 0, GappingThresholds.MIN_SHOTS),
        )
        assertThat(analysis.gaps.single().longer).isEqualTo(GolfClub.IRON_7)
        assertThat(analysis.gaps.single().shorter).isEqualTo(GolfClub.PITCHING_WEDGE)
        assertThat(analysis.gaps.single().gapYards).isCloseTo(15.0, TOLERANCE)
    }

    @Test
    fun anEmptyBagHasNothingToSay() {
        val analysis = analyzeGapping(emptyList())

        assertThat(analysis.gaps).isEmpty()
        assertThat(analysis.insights).isEmpty()
    }

    @Test
    fun bandOverlapIsZeroWithoutSpread() {
        val a = summarizeClubDistances(carries(150.0))!!
        val b = summarizeClubDistances(carries(152.0))!!

        assertThat(bandOverlap(a, b)).isCloseTo(0.0, TOLERANCE)
        assertThat(analyzeGapping(listOf(GappingClub(GolfClub.IRON_7, a))).insights.single())
            .isInstanceOf(GapInsight.InsufficientData::class)
    }

    /** Six shots at [carry] − [spread], [carry], [carry] + [spread] (twice): mean [carry]. */
    private fun measured(
        club: GolfClub,
        carry: Double,
        spread: Double = 3.0,
    ): GappingClub {
        val shots = List(2) { listOf(carry - spread, carry, carry + spread) }.flatten()
        val summary = summarizeClubDistances(shots.map { ClubDistanceSample(carryYards = it) })!!
        // Make the requested σ exact for the overlap maths.
        return GappingClub(club, summary.copy(stdDevCarryYards = spread))
    }

    private fun carries(vararg yards: Double) = yards.map { ClubDistanceSample(carryYards = it) }

    private companion object {
        const val TOLERANCE = 1e-9

        /** The default 14-club bag (plan A10) with a textbook set of carries. */
        val STANDARD_BAG =
            listOf(
                GolfClub.DRIVER to 250.0,
                GolfClub.WOOD_3 to 230.0,
                GolfClub.WOOD_5 to 215.0,
                GolfClub.HYBRID_3 to 200.0,
                GolfClub.IRON_4 to 190.0,
                GolfClub.IRON_5 to 180.0,
                GolfClub.IRON_6 to 170.0,
                GolfClub.IRON_7 to 160.0,
                GolfClub.IRON_8 to 150.0,
                GolfClub.IRON_9 to 140.0,
                GolfClub.PITCHING_WEDGE to 130.0,
                GolfClub.GAP_WEDGE to 118.0,
                GolfClub.SAND_WEDGE to 105.0,
                GolfClub.LOB_WEDGE to 90.0,
            )
    }
}
