// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One visit to the mat: a session spans a single transport connection, so every successful
 * (re)connect starts a new one (Expo `useSessionStore.startSession`, plan R8h). A row is written
 * with the session's first shot, so a connection without shots leaves no row behind.
 *
 * Schema v2 (plan F3) adds imported sessions: someone else's session, shared as a file. The Pi's
 * timestamp deletes and "clear all history" never touch them ([ShotHistoryDao]), and they stay out
 * of stats until the user opts them in ([includeInStats]).
 *
 * @property startedAtEpochMillis when the connection was established (phone clock), or, for an
 *   imported session, when the sharer recorded it.
 * @property host the Wi-Fi `host:port`, or `null` over Bluetooth.
 * @property transport `WIFI` or `BLUETOOTH` (`core:data`'s `TransportType` names).
 * @property source [SOURCE_LOCAL] or [SOURCE_IMPORTED].
 * @property ownerName who an imported session belongs to; `null` for the phone's own.
 * @property includeInStats whether the session's shots count in club stats and gapping.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMillis: Long,
    val host: String?,
    val transport: String,
    @ColumnInfo(defaultValue = "'LOCAL'") val source: String = SOURCE_LOCAL,
    @ColumnInfo(name = "owner_name") val ownerName: String? = null,
    val title: String? = null,
    @ColumnInfo(name = "include_in_stats", defaultValue = "1") val includeInStats: Boolean = true,
    val note: String? = null,
) {
    companion object {
        /** A session this phone recorded from its Pi. */
        const val SOURCE_LOCAL: String = "LOCAL"

        /** A session imported from a share file. */
        const val SOURCE_IMPORTED: String = "IMPORTED"
    }
}
