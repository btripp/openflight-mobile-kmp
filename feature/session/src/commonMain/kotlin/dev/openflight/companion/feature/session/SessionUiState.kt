// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/**
 * Where the session screen's shots and stats come from (plan R6b).
 * - [PI]: the Pi's Socket.IO session (`session_state`), while its link is connected.
 * - [LOCAL]: the phone's own SSE/BLE history, with `core:insights` stats. This covers Bluetooth
 *   and Wi-Fi without a Socket.IO link.
 */
enum class SessionSource {
    LOCAL,
    PI,
}

/**
 * The session/stats screen's state (plans R5a and R6b), ported from the web UI's
 * `StatsView.tsx` (club tabs and stats) and `ShotList.tsx` (the shot rows).
 *
 * @property selectedClub the selected tab's wire club value (e.g. `"7-iron"`), or `null` for the
 *   "All" tab.
 * @property allCount the "All" tab's count; [clubChips] gives one tab per club, in first-appearance
 *   order.
 * @property stats the selected tab's stats. On [SessionSource.PI] the "All" tab uses the server's
 *   own session stats.
 * @property swingStats non-null when every shot in the selected tab is a swing-speed rep (the web
 *   UI then shows Swings/Last/Best/Average instead of ball stats). Only the Pi reports swing reps.
 * @property shots the selected tab's shots, newest first, each keeping its session-wide `#n`. Unlike
 *   the web UI's list, the list follows the tab, like the stats and the dispersion chart.
 * @property dispersion the dispersion chart for the selected tab; `null` when no shot can be
 *   plotted (none yet, or only swing reps).
 * @property selectedShot the shot selected on the chart or in the list, while it's on the chart.
 * @property showSimulateShot the "Simulate Shot" button: visible only when the Pi runs `--mock`.
 * @property simulateLabel "Simulate Swing" in swing-speed mode, otherwise "Simulate Shot".
 * @property simulateAvailability why [SessionEvent.SimulateShot] can't run, if it can't.
 * @property editAvailability whether [SessionEvent.DeleteShot] and [SessionEvent.ClearHistory]
 *   can run: not over Bluetooth, a read-and-select link (plan R8e).
 * @property action the delete or clear in progress, from its confirmation to its outcome (plan R8f).
 * @property profileName the active Pi profile whose shots and stats are shown, on [SessionSource.PI].
 * @property staleNote set while nothing is connected: the shots shown are the last ones received.
 */
data class SessionUiState(
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val source: SessionSource = SessionSource.LOCAL,
    val allCount: Int = 0,
    val clubChips: List<ClubChip> = emptyList(),
    val selectedClub: String? = null,
    val stats: ClubStats = ClubStats.EMPTY,
    val swingStats: SwingSpeedStats? = null,
    val shots: List<SessionShotRow> = emptyList(),
    val dispersion: SessionDispersionUiState? = null,
    val selectedShot: SelectedShotCard? = null,
    val showSimulateShot: Boolean = false,
    val simulateLabel: String = SIMULATE_SHOT,
    val simulateAvailability: PiFeatureAvailability =
        PiFeatureAvailability.Unavailable(
            PiFeatureAvailability.NOT_CONNECTED,
        ),
    val editAvailability: PiFeatureAvailability = PiFeatureAvailability.Available,
    val action: SessionActionState = SessionActionState.Idle,
    val profileName: String? = null,
    val staleNote: String? = null,
) {
    val hasShots: Boolean get() = allCount > 0

    /**
     * Whether delete and clear controls are enabled: not over Bluetooth, and not while an action is
     * pending (so nothing is sent twice).
     */
    val canEdit: Boolean get() = editAvailability.isAvailable && !action.isBusy

    /** The stats card's tiles for the selected tab ([sessionStatTiles]). */
    val statTiles: List<SessionStatTile> get() = sessionStatTiles(stats, swingStats, units)

    companion object {
        const val SIMULATE_SHOT: String = "Simulate Shot"
        const val SIMULATE_SWING: String = "Simulate Swing"

        /** Expo `stats.tsx`: a dropped connection keeps the last session on screen, labelled as such. */
        const val STALE_NOTE: String = "Not connected — showing the last session received."
    }
}

/**
 * One row of the shot list (`ShotList.tsx`'s `ShotRow`).
 *
 * @property id what [SessionEvent.DeleteShot] takes: the shot's `eventId` on
 *   [SessionSource.LOCAL], its timestamp (the Pi's key) on [SessionSource.PI].
 * @property shotNumber `#n`, where 1 is the oldest shot.
 * @property ballSpeedMph `null` only for a Pi row that lacks it.
 * @property swingSpeedMph for a swing-speed rep: its club speed, else its ball speed.
 * @property profileName the Pi profile the shot was filed under, when known.
 * @property enrichment the Pi's confidence badges, carry range and profile, when known.
 */
data class SessionShotRow(
    val id: String,
    val shotNumber: Int,
    val timestamp: String,
    val club: String,
    val profileName: String?,
    val ballSpeedMph: Double?,
    val clubSpeedMph: Double?,
    val launchAngleVerticalDeg: Double?,
    val spinRpm: Double?,
    val carryYards: Double?,
    val isSwingSpeed: Boolean,
    val swingSpeedMph: Double?,
    val readingCount: Int?,
    val triggerSpeedMph: Double?,
    val durationMs: Double?,
    val implementLabel: String?,
    val enrichment: ShotEnrichment?,
)
