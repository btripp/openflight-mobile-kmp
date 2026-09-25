// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.TransportType

/**
 * The session history list (plan R8h): every stored session, newest first.
 *
 * @property loaded `false` until the stored sessions were read once (show nothing, not "empty").
 * @property isPersistent `false` when the database couldn't be opened and history lasts only until
 *   the app quits; the screen says so.
 */
data class SessionHistoryUiState(
    val loaded: Boolean = false,
    val sessions: List<SessionHistoryRow> = emptyList(),
    val isPersistent: Boolean = true,
)

/**
 * One past session.
 *
 * @property date the day of its first shot (`"2026-09-25"`, the Pi's local date).
 * @property timeRange first to last shot, `"10:03 – 10:45"`, or one time for a one-shot session.
 * @property transportLabel "Wi-Fi", "Bluetooth", or `null` when unknown.
 * @property isCurrent the session new shots are being filed under.
 */
data class SessionHistoryRow(
    val id: String,
    val date: String,
    val timeRange: String,
    val shotCount: Int,
    val transportLabel: String?,
    val isCurrent: Boolean,
) {
    /** "12 shots" / "1 shot". */
    val shotCountLabel: String get() = if (shotCount == 1) "1 shot" else "$shotCount shots"
}

/** Intents from the history list. */
sealed interface SessionHistoryEvent {
    /** Deletes every stored session. The screen asks for confirmation before sending it. */
    data object ClearAll : SessionHistoryEvent
}

/**
 * One stored session's detail: the same club tabs, stats and shot rows as the live Session screen
 * ([session], whose shot ids are timestamps), plus a heading.
 */
data class SessionHistoryDetailUiState(
    val loaded: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val session: SessionUiState = SessionUiState(),
)

/** Intents from a stored session's detail. */
sealed interface SessionHistoryDetailEvent {
    /** A club tab (`null` = All), like [SessionEvent.SelectClub]. */
    data class SelectClub(
        val club: String?,
    ) : SessionHistoryDetailEvent

    /** Removes one shot (a row's id, its timestamp) from the stored history. */
    data class DeleteShot(
        val id: String,
    ) : SessionHistoryDetailEvent

    /** Builds this session's CSV; the result arrives as [SessionEffect.CsvReady]. */
    data object ExportCsv : SessionHistoryDetailEvent
}

internal fun HistorySession.toRow(currentSessionId: String?): SessionHistoryRow =
    SessionHistoryRow(
        id = id,
        date = historyDate(firstShotAt),
        timeRange = historyTimeRange(firstShotAt, lastShotAt),
        shotCount = shotCount,
        transportLabel =
            when (transport) {
                TransportType.WIFI -> "Wi-Fi"
                TransportType.BLUETOOTH -> "Bluetooth"
                null -> null
            },
        isCurrent = id == currentSessionId,
    )

private const val HOURS_MINUTES_LENGTH = 5

/** `"2026-09-25T10:03:35.906612"` → `"2026-09-25"`. */
internal fun historyDate(timestamp: String): String = timestamp.substringBefore('T')

/** `"10:03 – 10:45"`, or just `"10:03"` when both ends fall in the same minute. */
internal fun historyTimeRange(
    first: String,
    last: String,
): String {
    val start = hoursMinutes(first)
    val end = hoursMinutes(last)
    return if (start == end) start else "$start – $end"
}

private fun hoursMinutes(timestamp: String): String =
    timestamp.substringAfter('T', missingDelimiterValue = timestamp).take(HOURS_MINUTES_LENGTH)
