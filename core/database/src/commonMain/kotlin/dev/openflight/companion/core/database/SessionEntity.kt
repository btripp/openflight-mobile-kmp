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
 * @property startedAtEpochMillis when the connection was established (phone clock).
 * @property host the Wi-Fi `host:port`, or `null` over Bluetooth.
 * @property transport `WIFI` or `BLUETOOTH` (`core:data`'s `TransportType` names).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMillis: Long,
    val host: String?,
    val transport: String,
)
