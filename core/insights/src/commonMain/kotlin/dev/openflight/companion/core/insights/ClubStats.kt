// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.ShotEvent

/**
 * Session stats for a set of shots, ported from the web UI's `computeStats`
 * (`ui/src/types/shot.ts`). Every average/max is over [shots]' `ball_speed_mph`/
 * `estimated_carry_yards`, which are never null on the wire; `avgClubSpeedMph` and
 * `avgSmashFactor` ignore shots that didn't report those (nullable) fields, the same way the web
 * UI does.
 */
data class ClubStats(
    val shotCount: Int,
    val avgBallSpeedMph: Double,
    val maxBallSpeedMph: Double,
    val avgCarryYards: Double,
    val avgClubSpeedMph: Double?,
    val avgSmashFactor: Double?,
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
            )
    }
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
