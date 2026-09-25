// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** User intents from the session/stats screen, sent up to [SessionViewModel.onEvent] (plans R5a, R6b). */
sealed interface SessionEvent {
    /** Switches the stats tab: a wire club value (e.g. `"7-iron"`), or `null` for "All". */
    data class SelectClub(
        val club: String?,
    ) : SessionEvent

    /**
     * Deletes one shot by its [SessionShotRow.id]. The shot is removed from the phone's history,
     * and from the Pi's session (by timestamp) while the Pi's Socket.IO link is connected.
     */
    data class DeleteShot(
        val id: String,
    ) : SessionEvent

    /** Clears the phone's history, and the Pi's session (`clear_session`) while its link is connected. */
    data object ClearHistory : SessionEvent

    /** Builds the CSV export; the result arrives through [SessionEffect.CsvReady]. */
    data object ExportCsv : SessionEvent

    /**
     * Selects a shot on the dispersion chart (a [SessionShotRow.id]), or clears the selection
     * with `null`. Selecting a shot from another club switches back to the "All" tab.
     */
    data class SelectShot(
        val id: String?,
    ) : SessionEvent

    /** Asks a `--mock` Pi for a fake shot (`simulate_shot`); see [SessionUiState.showSimulateShot]. */
    data object SimulateShot : SessionEvent
}
