// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.FlightParameter

/**
 * Where a shot finished side to side.
 *
 * @property offlineYards positive right of the target line.
 * @property sideEstimated the shot reported neither horizontal launch nor spin axis, so it sits on
 *   the target line by default rather than by measurement.
 */
data class OfflineDistance(
    val offlineYards: Double,
    val sideEstimated: Boolean,
)

/**
 * Offline distance for a shot. Nothing on the wire reports it: it's the landing point of
 * `core:flight`'s simulated trajectory, scaled to the reported carry (the simulator's default
 * `constrainToTargetCarry`). Missing launch or spin is filled per club by [FlightInputResolver].
 *
 * Hoisted from the Session dispersion chart (plan F5, A13) so the bag's club detail and the games
 * (F9) share one definition. Stateless; callers cache per shot if they fly the same shot again.
 */
class OfflineDistanceEstimator(
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val simulator: BallFlightSimulator = BallFlightSimulator(),
) {
    /** `null` when the shot can't be flown (no usable ball speed or carry). */
    fun estimate(measurements: FlightMeasurements): OfflineDistance? {
        val input =
            try {
                resolver.resolve(measurements)
            } catch (_: FlightInputResolutionError) {
                return null
            }
        val trajectory = simulator.simulate(input)
        val estimated = input.provenance.estimatedParameters
        return OfflineDistance(
            offlineYards = trajectory.lateralMeters / METERS_PER_YARD,
            sideEstimated = FlightParameter.HORIZONTAL_LAUNCH in estimated && FlightParameter.SPIN_AXIS in estimated,
        )
    }

    private companion object {
        const val METERS_PER_YARD = 0.9144
    }
}
