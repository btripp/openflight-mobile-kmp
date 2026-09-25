// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.GolfClub
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
 * - Schema v2 (plan F3): a session is the phone's own ([SessionSource.LOCAL]) or imported from a
 *   share file ([importSession], [SessionSource.IMPORTED]). The Pi's deletes and [clearAll] only
 *   ever touch the phone's own sessions, since an imported session may hold the same timestamps
 *   (A8); [clearImported] is the separate "delete imported sessions". Live views list only the
 *   phone's own sessions unless asked, and stats ([shotsForClub]) count only sessions whose
 *   [HistorySession.includeInStats] is on (off for an import until the user opts it in).
 *
 * Writes never throw and never block the caller: they are applied in order on the repository's
 * own scope. If the database can't be opened, it logs once and keeps this launch's history in
 * memory instead ([isPersistent] is then `false`); a failing read degrades to an empty list.
 */
@Suppress("TooManyFunctions") // Reads, live writes, and the user's edits (R8h, F3).
interface ShotHistoryRepository {
    /** `false` once the on-disk database failed to open and history lives only in memory. */
    val isPersistent: StateFlow<Boolean>

    /** The session new shots are filed under, or `null` before the first connect. */
    val currentSessionId: StateFlow<String?>

    /**
     * Sessions that hold at least one shot, newest first (by their newest shot). Only the phone's
     * own sessions unless [includeImported].
     */
    fun sessions(includeImported: Boolean = false): Flow<List<HistorySession>>

    /** One session's shots, newest first, optionally only [profileId]'s. */
    fun shots(
        sessionId: String,
        profileId: String? = null,
    ): Flow<List<HistoryShot>>

    /**
     * [club]'s shots across every session that counts in stats (the phone's own, plus imports the
     * user opted in), newest session first, then newest shot first; optionally only [profileId]'s
     * and only within [window].
     */
    fun shotsForClub(
        club: GolfClub,
        window: ShotWindow = ShotWindow.All,
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

    /** Deletes the phone's own sessions and their shots, never an imported one. The UI confirms first. */
    fun clearAll()

    /** Deletes every imported session and its shots. The UI confirms first. */
    fun clearImported()

    /** Stars or un-stars the stored shot [shotId] ([HistoryShot.id]). */
    fun setStarred(
        shotId: Long,
        starred: Boolean,
    )

    /** Sets (or, with `null`, removes) the user's note on the stored shot [shotId]. */
    fun setNote(
        shotId: Long,
        note: String?,
    )

    /** Whether session [sessionId]'s shots count in club stats and gapping. */
    fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    )

    /** Sets (or, with `null`, removes) the user's note on session [sessionId]. */
    fun setSessionNote(
        sessionId: String,
        note: String?,
    )

    /**
     * Stores [session] as a new [SessionSource.IMPORTED] session, out of stats until opted in.
     * It is never the [currentSessionId] and live shots are never filed under it. Profile ids and
     * names are dropped (they are the sharer's Pi's, not this one's), and a shot number repeated
     * within the file is kept only on its first shot.
     *
     * @return the new session's id, or `null` when there is no database to store it in.
     */
    suspend fun importSession(session: ImportedSession): String?
}

/** Where a session came from. */
enum class SessionSource {
    /** Recorded by this phone from its Pi. */
    LOCAL,

    /** Imported from a share file. */
    IMPORTED,
}

/** Which of a club's shots [ShotHistoryRepository.shotsForClub] returns. */
sealed interface ShotWindow {
    /** Every stored shot. */
    data object All : ShotWindow

    /** Shots from the [count] most recent sessions that hold one of the club's shots. */
    data class LastSessions(
        val count: Int,
    ) : ShotWindow {
        init {
            require(count > 0) { "count must be positive, was $count" }
        }
    }

    /** Shots from sessions started at or after [epochMillis]. */
    data class Since(
        val epochMillis: Long,
    ) : ShotWindow
}

/**
 * A session read from a share file, ready for [ShotHistoryRepository.importSession] (the file
 * format and its validation belong to the share codec).
 *
 * @property ownerName whose session it is, as the file says.
 * @property startedAtEpochMillis when the owner recorded it.
 * @property shots its shots' measurements.
 */
data class ImportedSession(
    val ownerName: String?,
    val title: String?,
    val startedAtEpochMillis: Long,
    val shots: List<ShotDetail>,
    val note: String? = null,
)

/**
 * One past session, for the history list.
 *
 * @property transport `null` for a session started without a known transport.
 * @property firstShotAt/lastShotAt the Pi's timestamps (naive local ISO) of the oldest and newest shot.
 * @property ownerName whose session an imported one is; `null` for the phone's own.
 * @property includeInStats whether its shots count in club stats and gapping.
 */
data class HistorySession(
    val id: String,
    val startedAtEpochMillis: Long,
    val host: String?,
    val transport: TransportType?,
    val shotCount: Int,
    val firstShotAt: String,
    val lastShotAt: String,
    val source: SessionSource = SessionSource.LOCAL,
    val ownerName: String? = null,
    val title: String? = null,
    val includeInStats: Boolean = true,
    val note: String? = null,
)

/**
 * One stored shot. [detail] is rebuilt from the stored columns in the Pi's `shot_to_dict` shape,
 * so the Session list, stats and CSV code that already reads [ShotDetail] renders it unchanged.
 *
 * @property id the row id, stable for the life of the row.
 * @property eventId the SSE/BLE event id, when the shot arrived that way.
 * @property starred/note the user's own marks on the shot.
 */
data class HistoryShot(
    val id: Long,
    val sessionId: String,
    val eventId: String?,
    val detail: ShotDetail,
    val starred: Boolean = false,
    val note: String? = null,
)
