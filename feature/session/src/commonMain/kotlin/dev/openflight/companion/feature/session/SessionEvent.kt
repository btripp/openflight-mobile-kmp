// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** User intents from the session/stats screen, sent up to [SessionViewModel.onEvent] (plans R5a, R6b). */
sealed interface SessionEvent {
    /** Switches the stats tab: a wire club value (e.g. `"7-iron"`), or `null` for "All". */
    data class SelectClub(
        val club: String?,
    ) : SessionEvent

    /**
     * Asks to delete one shot by its [SessionShotRow.id]: [SessionUiState.action] becomes a
     * confirmation. Once confirmed ([ConfirmAction]) the shot is removed from the phone's history,
     * and from the Pi's session (by timestamp, once the Pi confirms) while its link is connected.
     */
    data class DeleteShot(
        val id: String,
    ) : SessionEvent

    /**
     * Asks to clear the session: a confirmation first, like [DeleteShot]. Confirmed, it clears the
     * active profile's shots from the Pi's session (`clear_session`) while its link is connected,
     * else the phone's own list.
     */
    data object ClearHistory : SessionEvent

    /** Runs the action [SessionUiState.action] is confirming. */
    data object ConfirmAction : SessionEvent

    /** Drops the confirmation without doing anything. */
    data object CancelAction : SessionEvent

    /** Runs a failed action again, when [SessionActionState.Failed.canRetry]. */
    data object RetryAction : SessionEvent

    /** Hides a finished action's outcome ([SessionActionState.Done] or [SessionActionState.Failed]). */
    data object DismissAction : SessionEvent

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
