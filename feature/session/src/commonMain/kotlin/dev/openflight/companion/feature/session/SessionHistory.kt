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
 * @property action "Clear all history", from its confirmation to its outcome (plan R8f).
 */
data class SessionHistoryUiState(
    val loaded: Boolean = false,
    val sessions: List<SessionHistoryRow> = emptyList(),
    val isPersistent: Boolean = true,
    val action: SessionActionState = SessionActionState.Idle,
)

/**
 * One past session.
 *
 * @property date the day of its first shot, e.g. `"Thu 25 Sep"` (the Pi's local date; the year is
 *   added when it isn't this year's).
 * @property spokenDate the same day for a screen reader, e.g. `"Thursday 25 September"`.
 * @property timeRange first to last shot, `"10:03 – 10:45"`, or one time for a one-shot session.
 * @property transportLabel "Wi-Fi", "Bluetooth", or `null` when unknown.
 * @property host the Pi it was recorded from, when known (Wi-Fi only).
 * @property isCurrent the session new shots are being filed under.
 */
data class SessionHistoryRow(
    val id: String,
    val date: String,
    val timeRange: String,
    val shotCount: Int,
    val transportLabel: String?,
    val isCurrent: Boolean,
    val host: String? = null,
    val spokenDate: String = date,
) {
    /** "12 shots" / "1 shot". */
    val shotCountLabel: String get() = if (shotCount == 1) "1 shot" else "$shotCount shots"

    /** The line under the date: time range, then how it was connected. */
    val detailLine: String get() = listOfNotNull(timeRange, transportLabel, host).joinToString(" · ")

    /** One screen-reader stop for the whole row. */
    val accessibilityLabel: String
        get() =
            listOfNotNull(
                spokenDate,
                timeRange.replace(" – ", " to "),
                shotCountLabel,
                transportLabel,
                host,
                if (isCurrent) CURRENT_LABEL.lowercase() + " session" else null,
            ).joinToString(", ")

    companion object {
        const val CURRENT_LABEL: String = "Current"
    }
}

/** Intents from the history list. */
sealed interface SessionHistoryEvent {
    /** Asks to delete every stored session: [SessionHistoryUiState.action] becomes a confirmation. */
    data object ClearAll : SessionHistoryEvent

    data object ConfirmAction : SessionHistoryEvent

    data object CancelAction : SessionHistoryEvent

    data object RetryAction : SessionHistoryEvent

    data object DismissAction : SessionHistoryEvent
}

/** One profile's chip on a stored session's detail: its id, name and shot count. */
data class HistoryProfileChip(
    val id: String,
    val name: String,
    val count: Int,
)

/**
 * One stored session's detail: the same club tabs, stats and shot rows as the live Session screen
 * ([session], whose shot ids are timestamps), plus a heading.
 *
 * @property sourceLine how it was recorded ("Wi-Fi · raspberrypi.local:8080").
 * @property isCurrent the session new shots are still being filed under.
 * @property profileChips one per profile with shots here, when there are at least two; empty
 *   otherwise (there is nothing to filter).
 * @property selectedProfileId the profile filter; `null` shows every profile.
 * @property action a shot delete, from its confirmation to its outcome (plan R8f).
 */
data class SessionHistoryDetailUiState(
    val loaded: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val session: SessionUiState = SessionUiState(),
    val sourceLine: String? = null,
    val isCurrent: Boolean = false,
    val profileChips: List<HistoryProfileChip> = emptyList(),
    val selectedProfileId: String? = null,
    val action: SessionActionState = SessionActionState.Idle,
) {
    /** Whether a row can be deleted now: not while another delete is pending. */
    val canDelete: Boolean get() = !action.isBusy
}

/** Intents from a stored session's detail. */
sealed interface SessionHistoryDetailEvent {
    /** A club tab (`null` = All), like [SessionEvent.SelectClub]. */
    data class SelectClub(
        val club: String?,
    ) : SessionHistoryDetailEvent

    /** A profile chip (`null` = every profile). */
    data class SelectProfile(
        val profileId: String?,
    ) : SessionHistoryDetailEvent

    /** Asks to remove one shot (a row's id, its timestamp) from the stored history; confirmed first. */
    data class DeleteShot(
        val id: String,
    ) : SessionHistoryDetailEvent

    /** Builds this session's CSV; the result arrives as [SessionEffect.CsvReady]. */
    data object ExportCsv : SessionHistoryDetailEvent

    data object ConfirmAction : SessionHistoryDetailEvent

    data object CancelAction : SessionHistoryDetailEvent

    data object RetryAction : SessionHistoryDetailEvent

    data object DismissAction : SessionHistoryDetailEvent
}

internal fun HistorySession.toRow(
    currentSessionId: String?,
    currentYear: Int? = null,
): SessionHistoryRow =
    SessionHistoryRow(
        id = id,
        date = historyDay(firstShotAt, currentYear),
        timeRange = historyTimeRange(firstShotAt, lastShotAt),
        shotCount = shotCount,
        transportLabel = transportLabel(transport),
        isCurrent = id == currentSessionId,
        host = host?.takeIf { it.isNotBlank() },
        spokenDate = historySpokenDay(firstShotAt),
    )

internal fun transportLabel(transport: TransportType?): String? =
    when (transport) {
        TransportType.WIFI -> "Wi-Fi"
        TransportType.BLUETOOTH -> "Bluetooth"
        null -> null
    }

private const val HOURS_MINUTES_LENGTH = 5

/** `"2026-09-25T10:03:35.906612"` → `"2026-09-25"`. */
internal fun historyDate(timestamp: String): String = timestamp.substringBefore('T')

/**
 * `"2026-09-25T10:03:35.906612"` → `"Thu 25 Sep"`, with the year appended when it isn't
 * [currentYear] (`"Wed 24 Sep 2025"`). The Pi's timestamps are naive local times, so no time zone
 * is applied. Anything that isn't an ISO date is returned as it is.
 */
internal fun historyDay(
    timestamp: String,
    currentYear: Int? = null,
): String {
    val date = CalendarDate.parse(historyDate(timestamp)) ?: return historyDate(timestamp)
    val day = "${SHORT_WEEKDAYS[date.weekdayIndex]} ${date.day} ${SHORT_MONTHS[date.month - 1]}"
    return if (currentYear != null && currentYear != date.year) "$day ${date.year}" else day
}

/** `"2026-09-25T10:03:35"` → `"Thursday 25 September 2026"`, for a screen reader. */
internal fun historySpokenDay(timestamp: String): String {
    val date = CalendarDate.parse(historyDate(timestamp)) ?: return historyDate(timestamp)
    return "${WEEKDAYS[date.weekdayIndex]} ${date.day} ${MONTHS[date.month - 1]} ${date.year}"
}

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

/** A `yyyy-mm-dd` date, with its weekday (0 = Monday), computed without a date library. */
private data class CalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    /** Sakamoto's method: 0 = Sunday there, shifted so 0 = Monday. */
    val weekdayIndex: Int
        get() {
            val y = if (month < MARCH) year - 1 else year
            val sundayBased =
                (y + y / LEAP_EVERY - y / CENTURY + y / LEAP_CENTURY + MONTH_OFFSETS[month - 1] + day) % DAYS_PER_WEEK
            return (sundayBased + DAYS_PER_WEEK - 1) % DAYS_PER_WEEK
        }

    companion object {
        private const val MARCH = 3
        private const val LEAP_EVERY = 4
        private const val CENTURY = 100
        private const val LEAP_CENTURY = 400
        private const val DAYS_PER_WEEK = 7
        private const val MONTHS_PER_YEAR = 12
        private const val MAX_DAY = 31
        private val MONTH_OFFSETS = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)

        private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        fun parse(text: String): CalendarDate? {
            val (year, month, day) = ISO_DATE.matchEntire(text)?.destructured ?: return null
            return CalendarDate(year.toInt(), month.toInt(), day.toInt())
                .takeIf { it.month in 1..MONTHS_PER_YEAR && it.day in 1..MAX_DAY }
        }
    }
}

private val SHORT_WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val SHORT_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTHS =
    listOf(
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December",
    )
