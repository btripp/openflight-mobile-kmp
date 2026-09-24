// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** One-shot signals from [SessionViewModel] (plans R5a, R6b). */
sealed interface SessionEffect {
    /**
     * [SessionEvent.ExportCsv]'s result: the CSV document's full text and a suggested [filename].
     * The platform share sheet (Android's share intent, iOS's `ShareLink`) is the UI's job; this
     * ViewModel only produces the data.
     */
    data class CsvReady(
        val csv: String,
        val filename: String,
    ) : SessionEffect

    /** A message for a snackbar/toast: a Pi error such as "Shot not found", or a failed command. */
    data class Message(
        val text: String,
    ) : SessionEffect
}
