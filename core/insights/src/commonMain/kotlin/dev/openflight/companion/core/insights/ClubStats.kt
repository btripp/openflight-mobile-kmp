// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.ShotEvent
import kotlin.math.sqrt

/**
 * Session stats for a set of shots, ported from the kiosk's `computeStats`
 * (`ui/src/types/shot.ts`) as the Expo app mirrors it (`utils/sessionStats.ts`). Every average,
 * bound and spread is over the shots' ball speed/carry, which are never null on the wire;
 * [avgClubSpeedMph] and [avgSmashFactor] are averaged only over the shots that reported them, and
 * are `null` (not 0) when none did. An empty session is all zeros, like the kiosk's.
 *
 * @property stdDevBallSpeedMph the **sample** standard deviation (divides by n − 1) of ball speed;
 *   0 for fewer than two shots.
 */
data class ClubStats(
    val shotCount: Int,
    val avgBallSpeedMph: Double,
    val maxBallSpeedMph: Double,
    val avgCarryYards: Double,
    val avgClubSpeedMph: Double?,
    val avgSmashFactor: Double?,
    val minBallSpeedMph: Double = 0.0,
    val stdDevBallSpeedMph: Double = 0.0,
) {
    companion object {
        val EMPTY: ClubStats =
            ClubStats(
                shotCount = 0,
                avgBallSpeedMph = 0.0,
                maxBallSpeedMph = 0.0,
                avgCarryYards = 0.0,
                avgClubSpeedMph = null,
                avgSmashFactor = null,
                minBallSpeedMph = 0.0,
                stdDevBallSpeedMph = 0.0,
            )
    }
}

/**
 * The sample standard deviation (n − 1) of [values], or 0 with fewer than two: one shot has no
 * spread, and dividing by n − 1 would divide by zero (Expo `sessionStats.ts` `stdDev`).
 */
fun sampleStdDev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
}

/** One club's shot count, for the tab/chip row. Order matches [shots]' first-appearance order. */
data class ClubChip(
    val club: String,
    val count: Int,
)

/** Mirrors the web UI's `computeStats`. Returns [ClubStats.EMPTY] for an empty list. */
fun computeClubStats(shots: List<ShotEvent>): ClubStats {
    if (shots.isEmpty()) return ClubStats.EMPTY

    val ballSpeeds = shots.map { it.ballSpeedMph }
    val clubSpeeds = shots.mapNotNull { it.clubSpeedMph }
    val smashFactors = shots.mapNotNull { it.smashFactor }
    val carries = shots.map { it.estimatedCarryYards }

    return ClubStats(
        shotCount = shots.size,
        avgBallSpeedMph = ballSpeeds.average(),
        maxBallSpeedMph = ballSpeeds.max(),
        avgCarryYards = carries.average(),
        avgClubSpeedMph = clubSpeeds.takeIf { it.isNotEmpty() }?.average(),
        avgSmashFactor = smashFactors.takeIf { it.isNotEmpty() }?.average(),
        minBallSpeedMph = ballSpeeds.min(),
        stdDevBallSpeedMph = sampleStdDev(ballSpeeds),
    )
}

/**
 * One [ClubChip] per distinct [ShotEvent.club] in [shots], ordered by first appearance (mirrors
 * the web UI's `getUniqueClubs` + per-club `clubCounts`, `StatsView.tsx`).
 */
fun computeClubChips(shots: List<ShotEvent>): List<ClubChip> {
    val counts = LinkedHashMap<String, Int>()
    for (shot in shots) {
        counts[shot.club] = (counts[shot.club] ?: 0) + 1
    }
    return counts.map { (club, count) -> ClubChip(club, count) }
}
