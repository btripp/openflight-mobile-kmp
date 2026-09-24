// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

/** One-shot signals from [SessionViewModel] (plan R5a). */
sealed interface SessionEffect {
    /**
     * [SessionEvent.ExportCsv]'s result: the CSV document's full text and a suggested [filename].
     * The platform share sheet (Android's share intent, iOS's `ShareLink`) is R5b's job; this
     * ViewModel only produces the data.
     */
    data class CsvReady(
        val csv: String,
        val filename: String,
    ) : SessionEffect
}
