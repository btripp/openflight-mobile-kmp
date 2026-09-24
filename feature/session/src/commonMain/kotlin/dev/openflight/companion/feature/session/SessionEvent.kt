// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** User intents from the session/stats screen, sent up to [SessionViewModel.onEvent] (plan R5a). */
sealed interface SessionEvent {
    /** Switches the stats tab: a wire club value (e.g. `"7-iron"`), or `null` for "All". */
    data class SelectClub(
        val club: String?,
    ) : SessionEvent

    /** Deletes one shot locally (`ShotRepository.deleteShot`); the web UI does this over Socket.IO instead. */
    data class DeleteShot(
        val eventId: String,
    ) : SessionEvent

    /** Clears the whole history locally (`ShotRepository.clearHistory`). */
    data object ClearHistory : SessionEvent

    /** Builds the CSV export; the result arrives through [SessionEffect.CsvReady]. */
    data object ExportCsv : SessionEvent
}
