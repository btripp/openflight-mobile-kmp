// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.insights.DispersionViewport
import dev.openflight.companion.core.insights.UnitSystem
import kotlin.test.Test

class DispersionCopyTest {
    private val spread =
        ClubSpread(
            club = "7-iron",
            clubName = "7-Iron",
            shotCount = 5,
            avgCarryYards = 162.0,
            avgOfflineYards = 1.4,
            widthYards = 9.2,
            depthYards = 12.0,
            excludedCount = 1,
        )

    @Test
    fun theSpreadSummaryReadsCarryBiasAndArea() {
        assertThat(DispersionCopy.spreadSummary(spread, UnitSystem.IMPERIAL)).isEqualTo(
            "5 shots · 162 yds avg carry · 1 yds right · 9 yds wide × 12 yds deep · 1 possible bad read left out",
        )
    }

    @Test
    fun aSmallBiasReadsOnLineAndAMissingAreaSaysWhy() {
        val summary =
            DispersionCopy.spreadSummary(
                spread.copy(avgOfflineYards = -0.3, widthYards = null, depthYards = null, excludedCount = 0),
                UnitSystem.IMPERIAL,
            )

        assertThat(summary).isEqualTo("5 shots · 162 yds avg carry · on line · spread needs 3 shots with side data")
    }

    @Test
    fun metricSpreadIsInMetres() {
        val summary =
            DispersionCopy.spreadSummary(
                spread.copy(avgOfflineYards = -10.0, excludedCount = 0),
                UnitSystem.METRIC,
            )

        assertThat(summary).isEqualTo("5 shots · 148 m avg carry · 9 m left · 8 m wide × 11 m deep")
    }

    // region screen-reader text (plan R8f)

    private val points =
        listOf(
            // Newest first, like the chart's points.
            DispersionPoint("t4", 4, "7-iron", "7i", 1, carryYards = 165.0, offlineYards = 0.0, sideEstimated = true),
            DispersionPoint("t3", 3, "driver", "D", 0, carryYards = 262.0, offlineYards = 6.0, sideEstimated = false),
            DispersionPoint(
                "t2",
                2,
                "driver",
                "D",
                0,
                carryYards = 252.0,
                offlineYards = 2.0,
                sideEstimated = false,
                possibleBadRead = true,
            ),
            DispersionPoint("t1", 1, "7-iron", "7i", 1, carryYards = 161.0, offlineYards = -0.2, sideEstimated = true),
        )

    private val chart =
        SessionDispersionUiState(
            points = points,
            ellipses = emptyList(),
            viewport = DispersionViewport(150.0, 280.0, 20.0, emptyList()),
            estimatedSideCount = 2,
        )

    @Test
    fun theChartSummaryGivesEachClubsCountCarryAndSideInBagOrder() {
        assertThat(DispersionCopy.chartSummary(chart, UnitSystem.IMPERIAL)).isEqualTo(
            "Dispersion chart, 4 shots: 7-Iron, Driver. " +
                "Driver: 2 shots, 257 yds average carry, 4 yds right on average, 1 possible bad read. " +
                "7-Iron: 2 shots, 163 yds average carry, side not measured.",
        )
    }

    @Test
    fun aDotIsDescribedWithItsNumberClubCarryAndSide() {
        assertThat(DispersionCopy.pointDescription(points[1], UnitSystem.IMPERIAL))
            .isEqualTo("Shot 3, Driver, 262 yds carry, 6 yds right")
        assertThat(DispersionCopy.pointDescription(points[2], UnitSystem.METRIC))
            .isEqualTo("Shot 2, Driver, 230 m carry, 2 m right, possible bad read")
        assertThat(DispersionCopy.selectionDescription(chart, selectedId = null, UnitSystem.IMPERIAL))
            .isEqualTo(DispersionCopy.NO_SELECTION)
        assertThat(DispersionCopy.selectionDescription(chart, selectedId = "t4", UnitSystem.IMPERIAL))
            .isEqualTo("Shot 4, 7-Iron, 165 yds carry, side not measured")
    }

    @Test
    fun steppingGoesByShotNumberAndStopsAtTheEnds() {
        // Nothing selected: forward starts at the oldest, back at the newest.
        assertThat(DispersionCopy.adjacentShotId(points, null, forward = true)).isEqualTo("t1")
        assertThat(DispersionCopy.adjacentShotId(points, null, forward = false)).isEqualTo("t4")
        assertThat(DispersionCopy.adjacentShotId(points, "t2", forward = true)).isEqualTo("t3")
        assertThat(DispersionCopy.adjacentShotId(points, "t2", forward = false)).isEqualTo("t1")
        assertThat(DispersionCopy.adjacentShotId(points, "t4", forward = true)).isEqualTo(null)
        assertThat(DispersionCopy.adjacentShotId(points, "t1", forward = false)).isEqualTo(null)
    }

    // endregion
}
