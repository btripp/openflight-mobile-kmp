// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's persistent, multi-session shot history (plan R8h; the Expo app's
 * `storage/shotRepository.ts`, done with Room). The single source of truth for past sessions;
 * [ShotRepository] writes every live shot through it and keeps only the current session in memory.
 *
 * Rules (Expo's, ported):
 * - Every successful (re)connect starts a new session ([startSession]); a session is listed once
 *   it holds a shot.
 * - A shot is upserted within its session by `shot_number`, then by the SSE/BLE event id, then by
 *   timestamp to pair the SSE/BLE event with the Socket.IO detail of the same shot. So `shot` +
 *   `shot_update` (and BLE v2 provisional + final) file one row.
 * - A blank profile id or name is stored as absent.
 * - [deleteShot] mirrors a delete (every session's copy of that timestamp), [deleteShots] a
 *   per-profile `clear_session`; [clearAll] is the separate, confirmed "clear all history".
 *
 * Writes never throw and never block the caller: they are applied in order on the repository's
 * own scope. If the database can't be opened, it logs once and keeps this launch's history in
 * memory instead ([isPersistent] is then `false`); a failing read degrades to an empty list.
 */
interface ShotHistoryRepository {
    /** `false` once the on-disk database failed to open and history lives only in memory. */
    val isPersistent: StateFlow<Boolean>

    /** The session new shots are filed under, or `null` before the first connect. */
    val currentSessionId: StateFlow<String?>

    /** Sessions that hold at least one shot, newest first (by their newest shot). */
    fun sessions(): Flow<List<HistorySession>>

    /** One session's shots, newest first, optionally only [profileId]'s. */
    fun shots(
        sessionId: String,
        profileId: String? = null,
    ): Flow<List<HistoryShot>>

    /** Starts a new session: called on every successful (re)connect of the shot transport. */
    fun startSession(
        host: String?,
        transport: TransportType,
    )

    /** Files an SSE/BLE shot, with the Pi's Socket.IO [detail] for it when already known. */
    fun record(
        shot: ShotEvent,
        detail: ShotDetail? = null,
    )

    /** Files a live Socket.IO `shot`/`shot_update` ([PiSessionRepository.liveShots]). */
    fun record(shot: PiLiveShot)

    /** Mirrors a delete of the shot the Pi keys by [timestamp], in every session. */
    fun deleteShot(timestamp: String) = deleteShots(listOf(timestamp))

    /** Mirrors a delete of several shots, e.g. a profile's rows after `session_cleared`. */
    fun deleteShots(timestamps: Collection<String>)

    /** Deletes every session and shot. The UI confirms first. */
    fun clearAll()
}

/**
 * One past session, for the history list.
 *
 * @property transport `null` for a session started without a known transport.
 * @property firstShotAt/lastShotAt the Pi's timestamps (naive local ISO) of the oldest and newest shot.
 */
data class HistorySession(
    val id: String,
    val startedAtEpochMillis: Long,
    val host: String?,
    val transport: TransportType?,
    val shotCount: Int,
    val firstShotAt: String,
    val lastShotAt: String,
)

/**
 * One stored shot. [detail] is rebuilt from the stored columns in the Pi's `shot_to_dict` shape,
 * so the Session list, stats and CSV code that already reads [ShotDetail] renders it unchanged.
 *
 * @property id the row id, stable for the life of the row.
 * @property eventId the SSE/BLE event id, when the shot arrived that way.
 */
data class HistoryShot(
    val id: Long,
    val sessionId: String,
    val eventId: String?,
    val detail: ShotDetail,
)
