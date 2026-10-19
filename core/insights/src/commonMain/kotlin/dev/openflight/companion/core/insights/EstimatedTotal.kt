// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.ShotDistanceEstimate
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing

/**
 * Plan F5b: the estimated total (carry + roll) and roll for a shot row. Both are always estimates,
 * so [label] says so.
 */
data class EstimatedTotal(
    val totalYards: Double,
    val rollYards: Double,
) {
    /** "Total 247 yds · Roll 15 yds (est.)" in [units]. */
    fun label(units: UnitSystem): String =
        "Total ${formatDistance(totalYards, units)} · Roll ${formatDistance(rollYards, units)} (est.)"

    companion object {
        fun of(estimate: ShotDistanceEstimate): EstimatedTotal = EstimatedTotal(estimate.totalYards, estimate.rollYards)
    }
}

/**
 * [EstimatedTotal]s for a list of shots, flying each shot once per [Conditions] and target bearing
 * (two simulations a shot): results are kept per [FlightMeasurements] and dropped once a shot leaves
 * the list or the conditions change. Not thread-safe; call it from one flow.
 */
class EstimatedTotals(
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
) {
    private var cachedFor: Pair<Conditions, TargetBearing?>? = null
    private var cache: Map<FlightMeasurements, EstimatedTotal?> = emptyMap()

    /** Each shot's estimate keyed by [FlightMeasurements.id]; shots that can't be flown are left out. */
    fun of(
        shots: List<FlightMeasurements>,
        conditions: Conditions,
        targetBearing: TargetBearing?,
    ): Map<String, EstimatedTotal> {
        val key = conditions to targetBearing
        val previous = if (cachedFor == key) cache else emptyMap()
        val next = HashMap<FlightMeasurements, EstimatedTotal?>(shots.size)
        for (shot in shots) {
            next[shot] =
                if (shot in previous) {
                    previous[shot]
                } else {
                    estimator.estimate(shot, conditions, targetBearing)?.let(EstimatedTotal::of)
                }
        }
        cachedFor = key
        cache = next
        return buildMap { next.forEach { (shot, total) -> if (total != null) put(shot.id, total) } }
    }
}
