// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One shot's distances for a club summary.
 *
 * @property carryYards the carry the stats are about (the server's, or adjusted for conditions).
 * @property totalYards the estimated carry + roll, or `null` when it couldn't be estimated.
 * @property offlineYards positive right of the target line; `null` when the side wasn't measured
 *   (see [OfflineDistance.sideEstimated]), so it stays out of the offline stats.
 */
data class ClubDistanceSample(
    val carryYards: Double,
    val totalYards: Double? = null,
    val offlineYards: Double? = null,
)

/**
 * How far a club goes (plan F5): the "± yards" a fitter quotes, from the club's shots minus its
 * possible bad reads.
 *
 * Possible bad reads are found with the shared median/MAD rule, [findDispersionOutliers] (plan
 * A13), in carry and, for measured sides, offline. It needs [MIN_SHOTS_FOR_OUTLIERS] shots; below
 * that nothing is left out. They're counted in [excludedCount], never deleted.
 *
 * @property shotCount the shots kept (all of them minus [excludedCount]).
 * @property stdDevCarryYards the sample standard deviation (n − 1); 0 for one shot.
 * @property plusMinusYards [stdDevCarryYards] rounded to a whole yard, for "155 ± 5".
 * @property p10CarryYards/p90CarryYards percentiles, linearly interpolated between the closest
 *   ranks (rank `(n − 1)·p`, the common "type 7" definition).
 * @property meanTotalYards over the kept shots that have an estimated total; `null` when none do.
 * @property meanOfflineYards/stdDevOfflineYards over the kept shots with a measured side; `null`
 *   when none has one (the std dev is 0 for one).
 * @property outlierIndexes indexes into the input of the shots left out.
 */
data class ClubDistanceSummary(
    val shotCount: Int,
    val meanCarryYards: Double,
    val medianCarryYards: Double,
    val stdDevCarryYards: Double,
    val p10CarryYards: Double,
    val p90CarryYards: Double,
    val meanTotalYards: Double?,
    val meanOfflineYards: Double?,
    val stdDevOfflineYards: Double?,
    val excludedCount: Int,
    val outlierIndexes: Set<Int> = emptySet(),
) {
    val plusMinusYards: Int get() = stdDevCarryYards.roundToInt()
}

private const val P10 = 0.10
private const val P50 = 0.50
private const val P90 = 0.90

/** The [ClubDistanceSummary] of [samples] (one club's shots), or `null` when there are none. */
fun summarizeClubDistances(samples: List<ClubDistanceSample>): ClubDistanceSummary? {
    if (samples.isEmpty()) return null
    val outliers =
        findDispersionOutliers(
            samples.map { DispersionSample(offlineYards = it.offlineYards ?: 0.0, carryYards = it.carryYards) },
            samples.map { it.offlineYards != null },
        )
    val kept = samples.filterIndexed { index, _ -> index !in outliers }
    val carries = kept.map { it.carryYards }
    val totals = kept.mapNotNull { it.totalYards }
    val offlines = kept.mapNotNull { it.offlineYards }
    return ClubDistanceSummary(
        shotCount = kept.size,
        meanCarryYards = carries.average(),
        medianCarryYards = percentile(carries, P50),
        stdDevCarryYards = sampleStdDev(carries),
        p10CarryYards = percentile(carries, P10),
        p90CarryYards = percentile(carries, P90),
        meanTotalYards = totals.takeIf { it.isNotEmpty() }?.average(),
        meanOfflineYards = offlines.takeIf { it.isNotEmpty() }?.average(),
        stdDevOfflineYards = offlines.takeIf { it.isNotEmpty() }?.let(::sampleStdDev),
        excludedCount = outliers.size,
        outlierIndexes = outliers,
    )
}

/** The [p] percentile (0–1) of non-empty [values], interpolated between the closest ranks. */
internal fun percentile(
    values: List<Double>,
    p: Double,
): Double {
    val sorted = values.sorted()
    val rank = (sorted.size - 1) * p
    val lower = floor(rank).toInt()
    val upper = minOf(lower + 1, sorted.size - 1)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
}
