// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.OfflineDistanceEstimator
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.TargetBearing

/**
 * Turns a live shot into a [GameShot]: carry and total (est.) from `core:flight`'s
 * [ShotDistanceEstimator] under the current conditions, and the offline distance and apex from
 * `core:insights`' [OfflineDistanceEstimator], the same flight the bag and Session chart use.
 */
internal class GameShotMeasurer(
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
    private val offline: OfflineDistanceEstimator = OfflineDistanceEstimator(),
) {
    fun measure(
        shot: ShotEvent,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): GameShot {
        val measurements = shot.toFlightMeasurements()
        val estimate = estimator.estimate(measurements, conditions, targetBearing)
        val flight = offline.estimate(measurements)
        // No measured side (neither horizontal launch nor spin axis): the miss is distance-only.
        val offlineYards =
            flight?.takeUnless { it.sideEstimated }?.let {
                it.offlineYards + (estimate?.lateralDriftYards ?: 0.0)
            }
        return GameShot(
            eventId = shot.eventId,
            club = shot.club,
            carryYards = estimate?.carryYards ?: shot.estimatedCarryYards,
            carryEstimated = estimate?.isAdjusted == true,
            totalYards = estimate?.totalYards,
            offlineYards = offlineYards,
            apexYards = flight?.apexYards,
        )
    }
}
