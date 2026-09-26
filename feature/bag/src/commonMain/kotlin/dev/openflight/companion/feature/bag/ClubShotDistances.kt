// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.ClubDistanceSample
import dev.openflight.companion.core.insights.OfflineDistance
import dev.openflight.companion.core.insights.OfflineDistanceEstimator
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing

/**
 * One stored shot's distances for the bag screens.
 *
 * @property carryYards the server carry (`carry_spin_adjusted`, else the table carry), adjusted for
 *   the conditions when [carryAdjusted].
 * @property totalYards carry plus the estimated roll; `null` when the shot couldn't be flown.
 *   Always an estimate (plan §0.2), so the UI badges it "est.".
 * @property offlineYards positive right; `null` when the side wasn't measured.
 */
data class ClubShot(
    val shot: HistoryShot,
    val carryYards: Double,
    val totalYards: Double?,
    val offlineYards: Double?,
    val carryAdjusted: Boolean,
) {
    val sample: ClubDistanceSample get() = ClubDistanceSample(carryYards, totalYards, offlineYards)
}

/**
 * Turns stored shots into [ClubShot]s under the current conditions, flying each shot only once per
 * conditions (the estimator runs two simulations a shot). Swing-speed reps and rows without ball
 * speed or carry are left out. Not thread-safe: each ViewModel owns one and calls it from one flow.
 */
class ClubShotDistances(
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
    private val offline: OfflineDistanceEstimator = OfflineDistanceEstimator(),
) {
    private var estimates: Map<EstimateKey, ClubShotValues> = emptyMap()
    private var offlines: Map<FlightMeasurements, OfflineDistance?> = emptyMap()

    fun of(
        shots: List<HistoryShot>,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): List<ClubShot> {
        val nextEstimates = HashMap<EstimateKey, ClubShotValues>(shots.size)
        val nextOfflines = HashMap<FlightMeasurements, OfflineDistance?>(shots.size)
        val result =
            shots.mapNotNull { shot ->
                val measurements = shot.detail.toFlightMeasurements() ?: return@mapNotNull null
                val key = EstimateKey(measurements, conditions, targetBearing)
                val values = estimates[key] ?: values(measurements, conditions, targetBearing)
                nextEstimates[key] = values
                val side = if (measurements in offlines) offlines[measurements] else offline.estimate(measurements)
                nextOfflines[measurements] = side
                ClubShot(
                    shot = shot,
                    carryYards = values.carryYards,
                    totalYards = values.totalYards,
                    offlineYards = side?.takeUnless { it.sideEstimated }?.offlineYards,
                    carryAdjusted = values.adjusted,
                )
            }
        estimates = nextEstimates
        offlines = nextOfflines
        return result
    }

    private fun values(
        measurements: FlightMeasurements,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): ClubShotValues {
        val estimate = estimator.estimate(measurements, conditions, targetBearing)
        return if (estimate == null) {
            ClubShotValues(
                carryYards = measurements.carrySpinAdjustedYards?.takeIf { it > 0 } ?: measurements.carryYards,
                totalYards = null,
                adjusted = false,
            )
        } else {
            ClubShotValues(estimate.carryYards, estimate.totalYards, estimate.isAdjusted)
        }
    }

    private data class EstimateKey(
        val measurements: FlightMeasurements,
        val conditions: Conditions,
        val targetBearing: TargetBearing?,
    )

    private data class ClubShotValues(
        val carryYards: Double,
        val totalYards: Double?,
        val adjusted: Boolean,
    )
}
