// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import kotlin.test.Test

class EstimatedTotalTest {
    @Test
    fun theLabelSaysItIsAnEstimateInTheUsersUnits() {
        val total = EstimatedTotal(totalYards = 250.0, rollYards = 20.0)

        assertThat(total.label(UnitSystem.IMPERIAL)).isEqualTo("Total 250 yds · Roll 20 yds (est.)")
        assertThat(total.label(UnitSystem.METRIC)).isEqualTo("Total 229 m · Roll 18 m (est.)")
    }

    @Test
    fun aFlyableShotGetsATotalOfCarryPlusRollKeyedByItsId() {
        val totals = EstimatedTotals().of(listOf(sevenIron), Conditions.ISA, null)

        val total = totals[sevenIron.id]
        assertThat(total).isNotNull()
        assertThat(total!!.rollYards).isGreaterThan(0.0)
        assertThat(total.totalYards).isEqualTo(sevenIron.carryYards + total.rollYards)
    }

    @Test
    fun theSameConditionsReuseTheResultAndAFirmerSurfaceChangesTheRoll() {
        val totals = EstimatedTotals()
        val first = totals.of(listOf(sevenIron), Conditions.ISA, null)

        assertThat(
            totals.of(listOf(sevenIron), Conditions.ISA, null)[sevenIron.id],
        ).isSameInstanceAs(first[sevenIron.id])
        val firm = totals.of(listOf(sevenIron), Conditions.ISA.copy(surface = Firmness.FIRM), null)
        assertThat(firm[sevenIron.id]!!.rollYards).isNotEqualTo(first[sevenIron.id]!!.rollYards)
    }

    private val sevenIron =
        FlightMeasurements(
            id = "s1",
            club = "7-iron",
            ballSpeedMph = 120.0,
            carryYards = 165.0,
            launchAngleVertical = 16.0,
            spinRpm = 7_000.0,
        )
}
