// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlin.test.Test

class ConditionsAdjusterTest {
    private val adjuster = ConditionsAdjuster()
    private val driver = ConditionsLaunches.input(ConditionsLaunches.DRIVER)
    private val target = TargetBearing(90.0)
    private val tenMph = 10 * 0.44704

    private fun carry(
        conditions: Conditions,
        bearing: TargetBearing? = target,
    ) = adjuster.adjust(driver, anchorCarryYards = 250.0, conditions = conditions, targetBearing = bearing)

    @Test
    fun isaCalmLeavesTheServerCarry() {
        val result = carry(Conditions.ISA)

        assertThat(result.carryYards).isCloseTo(250.0, 0.1)
        assertThat(result.lateralDriftYards).isCloseTo(0.0, 0.01)
    }

    @Test
    fun carryRisesMonotonicallyAsTheAirThins() {
        val carries =
            listOf(0.0, 500.0, 1000.0, 1609.0, 2500.0).map { altitude ->
                carry(Conditions.ISA.copy(altitudeMeters = altitude)).carryYards
            }

        carries.zipWithNext().forEach { (lower, higher) -> assertThat(higher).isGreaterThan(lower) }
    }

    @Test
    fun resultIsDeterministic() {
        val conditions = Conditions.ISA.copy(altitudeMeters = 1609.0, wind = Wind(5.0, 45.0))

        assertThat(carry(conditions)).isEqualTo(carry(conditions))
    }

    @Test
    fun windFromTheTargetShortensCarry() {
        val headwind = carry(Conditions.ISA.copy(wind = Wind(tenMph, fromDegrees = 90.0)))

        assertThat(headwind.windApplied).isTrue()
        assertThat(headwind.carryYards).isLessThan(250.0)
    }

    @Test
    fun windFromBehindLengthensCarry() {
        val tailwind = carry(Conditions.ISA.copy(wind = Wind(tenMph, fromDegrees = 270.0)))

        assertThat(tailwind.carryYards).isGreaterThan(250.0)
    }

    @Test
    fun windFromTheLeftMovesTheBallRight() {
        // Hitting east (90°), the left is north (0°).
        val crosswind = carry(Conditions.ISA.copy(wind = Wind(tenMph, fromDegrees = 0.0)))

        assertThat(crosswind.lateralDriftYards).isGreaterThan(1.0)
    }

    @Test
    fun withoutATargetBearingOnlyDensityApplies() {
        val windy = Conditions.ISA.copy(altitudeMeters = 1609.0, wind = Wind(tenMph, fromDegrees = 90.0))
        val calm = windy.copy(wind = Wind.CALM)

        val noBearing = carry(windy, bearing = null)

        assertThat(noBearing.windApplied).isFalse()
        assertThat(noBearing.carryYards).isEqualTo(carry(calm, bearing = null).carryYards)
        assertThat(noBearing.lateralDriftYards).isCloseTo(0.0, 1e-9)
    }

    @Test
    fun densityRatioIsRelativeToIsa() {
        val result = carry(Conditions.ISA.copy(altitudeMeters = 1609.0))

        assertThat(result.densityRatio).isCloseTo(1.009 / 1.225, 0.01)
    }
}
