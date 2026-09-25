// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Plan F2 §0.2 plausibility bands (NORMAL turf): driver 5–40 yd, 7-iron 0–12, wedges 0–6, with
 * landings from the conditions simulator for the backend's representative launches.
 */
class RollEstimatorTest {
    private val estimator = RollEstimator()
    private val adjuster = ConditionsAdjuster()

    private fun roll(
        shot: FlightMeasurements,
        firmness: Firmness = Firmness.NORMAL,
    ): Double {
        val adjusted = adjuster.adjust(ConditionsLaunches.input(shot), shot.carryYards, Conditions.ISA, null)
        return estimator.estimate(adjusted.landing, adjusted.landingSpinRpm, firmness).rollYards.also {
            println("roll ${shot.club} $firmness: $it yd")
        }
    }

    private fun landing(
        speed: Double,
        descentDegrees: Double,
    ): FlightPoint {
        val angle = descentDegrees * PI / 180.0
        return FlightPoint(
            time = 6.0,
            positionMeters = Vec3(0.0, 0.0, 200.0),
            velocityMetersPerSecond = Vec3(0.0, -speed * sin(angle), speed * cos(angle)),
        )
    }

    @Test
    fun driverRollIsInTheNormalBand() {
        assertThat(roll(ConditionsLaunches.DRIVER)).isBetween(5.0, 40.0)
    }

    @Test
    fun sevenIronRollIsInTheNormalBand() {
        assertThat(roll(ConditionsLaunches.SEVEN_IRON)).isBetween(0.0, 12.0)
    }

    @Test
    fun wedgeRollIsInTheNormalBand() {
        assertThat(roll(ConditionsLaunches.PITCHING_WEDGE)).isBetween(0.0, 6.0)
    }

    @Test
    fun firmerTurfRollsFarther() {
        val soft = roll(ConditionsLaunches.DRIVER, Firmness.SOFT)
        val normal = roll(ConditionsLaunches.DRIVER, Firmness.NORMAL)
        val firm = roll(ConditionsLaunches.DRIVER, Firmness.FIRM)

        assertThat(firm).isGreaterThan(normal)
        assertThat(normal).isGreaterThan(soft)
    }

    @Test
    fun steeperDescentRollsLess() {
        val shallow = estimator.estimate(landing(28.0, 30.0), 2_000.0, Firmness.NORMAL).rollYards
        val steep = estimator.estimate(landing(28.0, 50.0), 2_000.0, Firmness.NORMAL).rollYards

        assertThat(steep).isLessThan(shallow)
    }

    @Test
    fun heavyBackspinNeverRollsNegative() {
        val spinBack = estimator.estimate(landing(20.0, 55.0), 11_000.0, Firmness.FIRM)

        assertThat(spinBack.rollYards).isGreaterThanOrEqualTo(0.0)
        assertThat(spinBack.provenance).isEqualTo(DistanceProvenance.ESTIMATED)
    }

    @Test
    fun verticalDescentBarelyRolls() {
        val straightDown = estimator.estimate(landing(20.0, 90.0), 0.0, Firmness.NORMAL)

        assertThat(straightDown.rollYards).isCloseTo(0.0, 0.5)
    }

    @Test
    fun referenceDriverLandingIsNearTheBackendHeuristic() {
        // Biber et al. 2023's "typical driver tee shot" landing: 93.6 ft/s, 37.3°, 34.9 rev/s.
        val reference = estimator.estimate(landing(93.6 * 0.3048, 37.3), 34.9 * 60, Firmness.NORMAL)
        val backendHeuristic = 30.0 * cos(37.3 * PI / 180.0)

        assertThat(reference.rollYards).isCloseTo(backendHeuristic, 5.0)
    }
}
