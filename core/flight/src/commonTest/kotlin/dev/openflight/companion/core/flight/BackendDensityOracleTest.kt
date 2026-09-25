// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.math.abs
import kotlin.test.Test

/**
 * The real test of the conditions adjustment (plan F2 §0.2, A11): our conditions-run carry ratio
 * (mile-high density / 1.225) must be within 1% of the backend's own `simulate(...,
 * air_density=)` ratio for the same launch, recorded in [BackendDensityOracle].
 */
class BackendDensityOracleTest {
    private fun carryMeters(
        case: BackendDensityOracle.Case,
        density: Double,
    ): Double {
        val input =
            ConditionsLaunches.input(
                ConditionsLaunches.measured(
                    club = case.club,
                    speedMph = case.ballSpeedMph,
                    launch = case.launchDegrees,
                    spin = case.spinRpm,
                    carry = case.seaLevelCarryYards,
                ),
            )
        val configuration = BallFlightSimulator.Configuration.conditions.copy(airDensity = density)
        return BallFlightSimulator(configuration).simulate(input).carryMeters
    }

    @Test
    fun carryRatioMatchesTheBackendWithinOnePercent() {
        for (case in BackendDensityOracle.cases) {
            val seaLevel = carryMeters(case, BackendDensityOracle.SEA_LEVEL_DENSITY)
            val appRatio = carryMeters(case, case.density) / seaLevel
            val error = abs(appRatio / case.carryRatio - 1.0)
            println("oracle ${case.name}: backend ${case.carryRatio} app $appRatio error ${error * 100}%")

            assertThat(appRatio).isGreaterThan(1.0)
            assertThat(error, name = case.name).isLessThan(0.01)
        }
    }

    @Test
    fun seaLevelCarryMatchesTheBackendWithinOnePercent() {
        for (case in BackendDensityOracle.cases) {
            val yards = carryMeters(case, BackendDensityOracle.SEA_LEVEL_DENSITY) / 0.9144

            assertThat(abs(yards / case.seaLevelCarryYards - 1.0), name = case.name).isLessThan(0.01)
        }
    }
}
