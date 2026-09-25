// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing
import kotlin.math.abs

/**
 * A server carry adjusted for [Conditions] (plan F2 §0.2).
 *
 * @property carryYards the anchor carry × [carryRatio].
 * @property lateralDriftYards extra sideways movement from the wind (+ right), the lateral
 *   landing difference between the conditions run and the ISA calm run; 0 without wind.
 * @property carryRatio conditions-run carry / ISA-calm-run carry.
 * @property airDensity the conditions' density in kg/m³.
 * @property densityRatio [airDensity] / [AirDensity.ISA_SEA_LEVEL].
 * @property windApplied whether the wind was included (it needs a target bearing).
 * @property landing the unconstrained conditions run's landing point, **unscaled** (scaling
 *   would distort the descent angle); the input to [RollEstimator].
 * @property landingSpinRpm the conditions run's spin at landing, after spin decay.
 */
data class AdjustedCarry(
    val carryYards: Double,
    val lateralDriftYards: Double,
    val carryRatio: Double,
    val airDensity: Double,
    val densityRatio: Double,
    val windApplied: Boolean,
    val landing: FlightPoint,
    val landingSpinRpm: Double,
)

/**
 * Adjusts the server's carry for air density and wind by the **ratio method**: the server carry
 * stays the anchor, and only the simulator's ratio between two unconstrained runs of the same
 * [FlightInput] is applied to it:
 *
 * `adjusted = anchor × sim(conditions, wind).carry / sim(ISA 1.225, calm).carry`.
 *
 * Both runs use [BallFlightSimulator.Configuration.conditions] (backend-equivalent aerodynamics
 * and spin decay, checked against the backend's own density ratios in `BackendDensityOracle`).
 * The wind is applied only with a [TargetBearing]; without one the adjustment is density only.
 */
class ConditionsAdjuster(
    private val baseConfiguration: BallFlightSimulator.Configuration = BallFlightSimulator.Configuration.conditions,
) {
    private val isaSimulator =
        BallFlightSimulator(
            baseConfiguration.copy(airDensity = AirDensity.ISA_SEA_LEVEL, constrainToTargetCarry = false),
        )

    fun adjust(
        input: FlightInput,
        anchorCarryYards: Double,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): AdjustedCarry {
        val density = AirDensity.of(conditions)
        val wind = targetBearing?.let { conditions.wind.toSimulatorFrame(it) } ?: Vec3.ZERO
        val windApplied = targetBearing != null && !conditions.wind.isCalm

        val calm = input.copy(windMetersPerSecond = Vec3.ZERO)
        val baseline = isaSimulator.simulate(calm)
        val adjusted =
            if (!windApplied && abs(density - AirDensity.ISA_SEA_LEVEL) < SAME_DENSITY_TOLERANCE) {
                baseline
            } else {
                BallFlightSimulator(baseConfiguration.copy(airDensity = density, constrainToTargetCarry = false))
                    .simulate(calm.copy(windMetersPerSecond = wind))
            }

        val ratio = if (baseline.carryMeters > 0) adjusted.carryMeters / baseline.carryMeters else 1.0
        val landing = adjusted.points.last()
        return AdjustedCarry(
            carryYards = anchorCarryYards * ratio,
            lateralDriftYards = (adjusted.lateralMeters - baseline.lateralMeters) * METERS_TO_YARDS,
            carryRatio = ratio,
            airDensity = density,
            densityRatio = density / AirDensity.ISA_SEA_LEVEL,
            windApplied = windApplied,
            landing = landing,
            landingSpinRpm = adjusted.landingSpinRpm ?: input.spinRpm,
        )
    }

    private companion object {
        /** Densities this close to ISA give the same flight; skip the second run. */
        const val SAME_DENSITY_TOLERANCE = 1e-9
        const val METERS_TO_YARDS = 1.0 / 0.9144
    }
}
