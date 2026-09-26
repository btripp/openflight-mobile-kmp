// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.insights.ClubDistanceSummary
import dev.openflight.companion.core.insights.summarizeClubDistances
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Each of [clubs]' stored shots in [window], as one map. Only sessions that count in stats are
 * read ([ShotHistoryRepository.shotsForClub]), so imported sessions stay out until the user opts
 * them in (plan D4).
 */
internal fun ShotHistoryRepository.shotsByClub(
    clubs: List<GolfClub>,
    window: ShotWindow,
): Flow<Map<GolfClub, List<HistoryShot>>> {
    if (clubs.isEmpty()) return flowOf(emptyMap())
    return combine(clubs.map { club -> shotsForClub(club, window) }) { perClub ->
        clubs.zip(perClub.toList()).toMap()
    }
}

/** A club's shots under the current conditions and their summary (`null` without shots). */
data class ClubStatsEntry(
    val shots: List<ClubShot>,
    val summary: ClubDistanceSummary?,
)

internal fun clubStats(shots: List<ClubShot>): ClubStatsEntry =
    ClubStatsEntry(shots, summarizeClubDistances(shots.map { it.sample }))

/**
 * Colour slots for the clubs of a bag: the bag's own order, so a club keeps its colour on every
 * bag screen.
 */
internal fun colorIndexes(clubs: List<GolfClub>): Map<GolfClub, Int> =
    clubs.withIndex().associate { (index, club) ->
        club to
            index
    }
