// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.UnitSystem

/**
 * The session/stats screen's state (plan R5a), ported from the web UI's `StatsView.tsx`: a tab
 * per club (plus "All"), and the stats for whichever tab is selected.
 *
 * @property selectedClub the selected tab's wire club value (e.g. `"7-iron"`), or `null` for the
 *   "All" tab.
 * @property clubChips one chip per club in [dev.openflight.companion.core.data.ShotRepository.history],
 *   ordered by first appearance, for rendering the per-club tabs; [allCount] is the "All" tab's
 *   count.
 * @property stats [selectedClub]'s shots' stats (or every shot's, for "All"), from
 *   `core:insights`'s `computeClubStats`.
 */
data class SessionUiState(
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val allCount: Int = 0,
    val clubChips: List<ClubChip> = emptyList(),
    val selectedClub: String? = null,
    val stats: ClubStats = ClubStats.EMPTY,
) {
    val hasShots: Boolean get() = allCount > 0
}
