// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.flight.BallFlightSimulator
import dev.openflight.companion.core.flight.FlightInputResolutionError
import dev.openflight.companion.core.flight.FlightInputResolver
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing

/**
 * Turns a live shot into a [GameShot]: carry and total (est.) from `core:flight`'s
 * [ShotDistanceEstimator] under the current conditions, and the offline distance and apex from a
 * simulated flight scaled to the reported carry.
 *
 * TODO(F5/A13): the offline distance duplicates the private helper in feature:session's
 * `SessionDispersion.kt` (fly the shot, read the lateral landing). F5 hoists that helper into
 * core:insights; once it's in this module's base, use it here and delete [offlineAndApex].
 */
internal class GameShotMeasurer(
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
    private val resolver: FlightInputResolver = FlightInputResolver(),
    private val simulator: BallFlightSimulator = BallFlightSimulator(),
) {
    fun measure(
        shot: ShotEvent,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): GameShot {
        val measurements = shot.toFlightMeasurements()
        val estimate = estimator.estimate(measurements, conditions, targetBearing)
        val flight = offlineAndApex(shot)
        // The plan's rule: no horizontal launch angle means no side, so the miss is distance-only.
        val offline =
            flight?.offlineYards?.takeIf { shot.launchAngleHorizontal != null }?.let {
                it + (estimate?.lateralDriftYards ?: 0.0)
            }
        return GameShot(
            eventId = shot.eventId,
            club = shot.club,
            carryYards = estimate?.carryYards ?: shot.estimatedCarryYards,
            carryEstimated = estimate?.isAdjusted == true,
            totalYards = estimate?.totalYards,
            offlineYards = offline,
            apexYards = flight?.apexYards,
        )
    }

    private fun offlineAndApex(shot: ShotEvent): Flight? {
        val input =
            try {
                resolver.resolve(shot.toFlightMeasurements())
            } catch (_: FlightInputResolutionError) {
                return null
            }
        val trajectory = simulator.simulate(input)
        return Flight(
            offlineYards = trajectory.lateralMeters / METERS_PER_YARD,
            apexYards = trajectory.apexMeters / METERS_PER_YARD,
        )
    }

    private data class Flight(
        val offlineYards: Double,
        val apexYards: Double,
    )

    private companion object {
        const val METERS_PER_YARD = 0.9144
    }
}
