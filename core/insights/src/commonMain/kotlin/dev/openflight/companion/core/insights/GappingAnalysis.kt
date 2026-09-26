// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.GolfClub
import kotlin.math.max
import kotlin.math.min

/**
 * The limits [analyzeGapping] flags against.
 *
 * These are a **heuristic**, not a measurement: the rules of thumb club fitters commonly quote for
 * a set's yardage gaps (roughly 10–15 yd between irons and wedges; more at the long end, where
 * woods are built further apart). They aren't from a study, and the UI words them as suggestions.
 */
object GappingThresholds {
    /** A gap under this many yards between neighbours is too tight to be worth two clubs. */
    const val MIN_GAP_YARDS: Double = 7.0

    /** Neighbours whose mean ± 1 σ carry bands overlap by more than this share are too tight. */
    const val MAX_BAND_OVERLAP: Double = 0.5

    /** A gap wider than this between two irons or wedges leaves a hole in the set. */
    const val MAX_GAP_IRONS_YARDS: Double = 20.0

    /** A gap wider than this when a driver, wood or hybrid is involved leaves a hole in the set. */
    const val MAX_GAP_WOODS_YARDS: Double = 30.0

    /** Fewer shots than this don't give a club a trustworthy average carry. */
    const val MIN_SHOTS: Int = 5
}

/** One club of the bag with its summary (`null` when it has no shots), for [analyzeGapping]. */
data class GappingClub(
    val club: GolfClub,
    val summary: ClubDistanceSummary?,
)

/** What's wrong with a [ClubGap], if anything. */
enum class GapFlag {
    TOO_TIGHT,
    TOO_WIDE,
    OUT_OF_ORDER,
}

/**
 * The carry gap between two neighbouring clubs (in bag order) that both have enough shots.
 *
 * @property gapYards [longer]'s mean carry minus [shorter]'s; negative when out of order.
 */
data class ClubGap(
    val longer: GolfClub,
    val shorter: GolfClub,
    val gapYards: Double,
    val flag: GapFlag?,
)

/** A finding about the bag's gapping: typed, so each platform words it (and speaks it) itself. */
sealed interface GapInsight {
    /**
     * [longer] and [shorter] carry about the same: the gap is under
     * [GappingThresholds.MIN_GAP_YARDS] or their carry bands overlap by more than
     * [GappingThresholds.MAX_BAND_OVERLAP] ([bandOverlap], 0–1).
     */
    data class TooTight(
        val longer: GolfClub,
        val shorter: GolfClub,
        val gapYards: Double,
        val bandOverlap: Double,
    ) : GapInsight

    /** The gap is wider than [limitYards] (a [GappingThresholds] maximum). */
    data class TooWide(
        val longer: GolfClub,
        val shorter: GolfClub,
        val gapYards: Double,
        val limitYards: Double,
    ) : GapInsight

    /** [longer], listed first in the bag, carries less than [shorter]. */
    data class OutOfOrder(
        val longer: GolfClub,
        val shorter: GolfClub,
        val longerCarryYards: Double,
        val shorterCarryYards: Double,
    ) : GapInsight

    /** [club] has [shotCount] shots, under [requiredShots]; it's left out of the gaps. */
    data class InsufficientData(
        val club: GolfClub,
        val shotCount: Int,
        val requiredShots: Int,
    ) : GapInsight
}

/**
 * The bag's gaps and what they suggest.
 *
 * @property gaps between each pair of neighbouring clubs with enough shots, in bag order.
 * @property insights clubs short of data first (in bag order), then one per flagged gap.
 */
data class GappingAnalysis(
    val gaps: List<ClubGap>,
    val insights: List<GapInsight>,
)

/**
 * Gaps between neighbouring clubs of [clubsInBagOrder] by mean carry. A club with fewer than
 * [GappingThresholds.MIN_SHOTS] kept shots gets [GapInsight.InsufficientData] and is skipped, so
 * its neighbours are compared with each other. A gap is flagged, in this order:
 * - [GapFlag.OUT_OF_ORDER] when it's negative;
 * - [GapFlag.TOO_TIGHT] under [GappingThresholds.MIN_GAP_YARDS], or when the mean ± 1 σ bands
 *   overlap by more than [GappingThresholds.MAX_BAND_OVERLAP] of the narrower band;
 * - [GapFlag.TOO_WIDE] over [GappingThresholds.MAX_GAP_WOODS_YARDS] when a driver, wood or hybrid
 *   is one of the pair, otherwise over [GappingThresholds.MAX_GAP_IRONS_YARDS].
 */
fun analyzeGapping(clubsInBagOrder: List<GappingClub>): GappingAnalysis {
    val insufficient =
        clubsInBagOrder
            .filter { (it.summary?.shotCount ?: 0) < GappingThresholds.MIN_SHOTS }
            .map { GapInsight.InsufficientData(it.club, it.summary?.shotCount ?: 0, GappingThresholds.MIN_SHOTS) }
    val measured =
        clubsInBagOrder.mapNotNull { entry ->
            entry.summary?.takeIf { it.shotCount >= GappingThresholds.MIN_SHOTS }?.let { entry.club to it }
        }
    val gapsWithInsights =
        measured.zipWithNext { (longer, longerSummary), (shorter, shorterSummary) ->
            gap(longer, longerSummary, shorter, shorterSummary)
        }
    return GappingAnalysis(
        gaps = gapsWithInsights.map { it.first },
        insights = insufficient + gapsWithInsights.mapNotNull { it.second },
    )
}

private fun gap(
    longer: GolfClub,
    longerSummary: ClubDistanceSummary,
    shorter: GolfClub,
    shorterSummary: ClubDistanceSummary,
): Pair<ClubGap, GapInsight?> {
    val gapYards = longerSummary.meanCarryYards - shorterSummary.meanCarryYards
    val overlap = bandOverlap(longerSummary, shorterSummary)
    val limit =
        if (longer.isWoodLike || shorter.isWoodLike) {
            GappingThresholds.MAX_GAP_WOODS_YARDS
        } else {
            GappingThresholds.MAX_GAP_IRONS_YARDS
        }
    val insight =
        when {
            gapYards < 0 -> {
                GapInsight.OutOfOrder(longer, shorter, longerSummary.meanCarryYards, shorterSummary.meanCarryYards)
            }

            gapYards < GappingThresholds.MIN_GAP_YARDS || overlap > GappingThresholds.MAX_BAND_OVERLAP -> {
                GapInsight.TooTight(longer, shorter, gapYards, overlap)
            }

            gapYards > limit -> {
                GapInsight.TooWide(longer, shorter, gapYards, limit)
            }

            else -> {
                null
            }
        }
    val flag =
        when (insight) {
            is GapInsight.OutOfOrder -> GapFlag.OUT_OF_ORDER
            is GapInsight.TooTight -> GapFlag.TOO_TIGHT
            is GapInsight.TooWide -> GapFlag.TOO_WIDE
            else -> null
        }
    return ClubGap(longer, shorter, gapYards, flag) to insight
}

/**
 * How much the mean ± 1 σ carry bands of two clubs overlap, as a share (0–1) of the narrower
 * band; 0 when either band has no width (one shot, or identical carries).
 */
internal fun bandOverlap(
    a: ClubDistanceSummary,
    b: ClubDistanceSummary,
): Double {
    val narrower = 2 * min(a.stdDevCarryYards, b.stdDevCarryYards)
    if (narrower <= 0.0) return 0.0
    val low = max(a.meanCarryYards - a.stdDevCarryYards, b.meanCarryYards - b.stdDevCarryYards)
    val high = min(a.meanCarryYards + a.stdDevCarryYards, b.meanCarryYards + b.stdDevCarryYards)
    return max(0.0, high - low) / narrower
}

/** Driver, woods and hybrids: built further apart, so they get the wider gap limit. */
private val GolfClub.isWoodLike: Boolean
    get() = this == GolfClub.DRIVER || wireValue.endsWith("-wood") || wireValue.endsWith("-hybrid")
