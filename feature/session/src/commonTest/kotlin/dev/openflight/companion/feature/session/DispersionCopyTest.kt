// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import assertk.assertThat
import assertk.assertions.isEqualTo
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
}
