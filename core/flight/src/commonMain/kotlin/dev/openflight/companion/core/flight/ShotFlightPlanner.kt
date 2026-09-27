// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing

/**
 * A shot's displayed distances and the flight drawn for them (plan F2b).
 *
 * [trajectory] lands exactly at [ShotDistanceEstimate.carryYards], so the drawn ball lands where
 * the carry label says, and it flies in the conditions' air (and wind, once a target bearing is
 * set).
 */
data class PlannedShot(
    val estimate: ShotDistanceEstimate,
    val trajectory: FlightTrajectory,
)

/**
 * The one entry point for a flight to draw (plan F2b). It runs [ShotDistanceEstimator] (the ISA
 * baseline run plus the conditions run), then fits the conditions run's drag to the displayed
 * carry with [BallFlightSimulator.Configuration.render] at the conditions' density. The
 * conditions run is reused as the fit's k = 1 starting point, so a shot costs the estimator's runs
 * plus the fit's few extra ones (none when the conditions run already lands within 0.1 yd).
 */
class ShotFlightPlanner(
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
    private val renderConfiguration: BallFlightSimulator.Configuration = BallFlightSimulator.Configuration.render,
) {
    /** The plan for [shot], or `null` when it has no usable ball speed or carry. */
    fun plan(
        shot: FlightMeasurements,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): PlannedShot? {
        val result = estimator.estimateWithRun(shot, conditions, targetBearing) ?: return null
        val adjusted = result.adjusted
        val input =
            result.input.copy(
                targetCarryMeters = result.estimate.carryYards * YARDS_TO_METERS,
                windMetersPerSecond = adjusted.windMetersPerSecond,
            )
        val simulator = BallFlightSimulator(renderConfiguration.copy(airDensity = adjusted.airDensity))
        return PlannedShot(result.estimate, simulator.fitToCarry(input, adjusted.run))
    }

    private companion object {
        const val YARDS_TO_METERS = 0.9144
    }
}
