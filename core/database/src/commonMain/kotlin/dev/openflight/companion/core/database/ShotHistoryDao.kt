// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/**
 * The shot history's reads and writes (plan R8h). Observers get [Flow]s that re-emit on every
 * change to the tables they read; writes are `suspend` and each runs in one transaction.
 */
@Dao
@Suppress("TooManyFunctions") // One function per query, as Room wants them.
abstract class ShotHistoryDao {
    /**
     * Sessions that hold at least one shot, newest first (by their newest shot, then by start).
     * A session whose last shot was deleted drops out on its own, like Expo's `loadSessions`.
     * Imported sessions are listed only with [includeImported].
     */
    @Query(
        """
        SELECT s.id AS id, s.started_at AS started_at, s.host AS host, s.transport AS transport,
               s.source AS source, s.owner_name AS owner_name, s.title AS title,
               s.include_in_stats AS include_in_stats, s.note AS note,
               COUNT(sh.id) AS shot_count,
               MIN(sh.timestamp) AS first_shot_at,
               MAX(sh.timestamp) AS last_shot_at
        FROM sessions s
        INNER JOIN shots sh ON sh.session_id = s.id
        WHERE :includeImported OR s.source = 'LOCAL'
        GROUP BY s.id
        ORDER BY last_shot_at DESC, s.started_at DESC
        """,
    )
    abstract fun observeSessions(includeImported: Boolean): Flow<List<SessionSummaryRow>>

    /** The phone's own sessions only (no imported ones): [observeSessions] for live history. */
    fun observeSessions(): Flow<List<SessionSummaryRow>> = observeSessions(includeImported = false)

    /** One session's shots, newest first; `id` breaks timestamp ties (Expo `loadShots`). */
    @Query("SELECT * FROM shots WHERE session_id = :sessionId ORDER BY timestamp DESC, id DESC")
    abstract fun observeShots(sessionId: String): Flow<List<ShotEntity>>

    /** [observeShots] limited to one Pi profile. */
    @Query(
        """
        SELECT * FROM shots WHERE session_id = :sessionId AND profile_id = :profileId
        ORDER BY timestamp DESC, id DESC
        """,
    )
    abstract fun observeShots(
        sessionId: String,
        profileId: String,
    ): Flow<List<ShotEntity>>

    /**
     * One club's shots across the sessions that count in stats (`include_in_stats`), newest session
     * first, then newest shot first. [club] is the wire value. Optionally only [profileId]'s shots,
     * only sessions started at or after [sinceEpochMillis], and only the [sessionLimit] most recent
     * sessions holding such a shot (`-1`: no limit, SQLite's `LIMIT -1`).
     */
    @Query(
        """
        SELECT sh.* FROM shots sh
        INNER JOIN sessions s ON s.id = sh.session_id
        WHERE sh.club = :club AND s.include_in_stats = 1 AND s.started_at >= :sinceEpochMillis
          AND (:profileId IS NULL OR sh.profile_id = :profileId)
          AND s.id IN (
            SELECT s2.id FROM sessions s2
            INNER JOIN shots sh2 ON sh2.session_id = s2.id
            WHERE sh2.club = :club AND s2.include_in_stats = 1 AND s2.started_at >= :sinceEpochMillis
              AND (:profileId IS NULL OR sh2.profile_id = :profileId)
            GROUP BY s2.id
            ORDER BY s2.started_at DESC, s2.id DESC
            LIMIT :sessionLimit
          )
        ORDER BY s.started_at DESC, s.id DESC, sh.timestamp DESC, sh.id DESC
        """,
    )
    abstract fun observeShotsForClub(
        club: String,
        profileId: String?,
        sinceEpochMillis: Long,
        sessionLimit: Int,
    ): Flow<List<ShotEntity>>

    /**
     * Files [shot] under [session]: updates the stored row of the same shot (see [ShotEntity] for
     * the identity order) or inserts a new one, and writes the session's row the first time. One
     * transaction, so a `shot` and its `shot_update` can never both insert.
     *
     * @return the row id.
     */
    @Transaction
    open suspend fun upsert(
        session: SessionEntity,
        shot: ShotEntity,
    ): Long {
        insertSession(session)
        val incoming = shot.copy(id = 0, sessionId = session.id)
        val existing =
            incoming.shotNumber?.let { findByShotNumber(session.id, it) }
                ?: incoming.eventId?.let { findByEventId(session.id, it) }
                ?: findByTimestamp(session.id, incoming.timestamp).firstOrNull { it.pairsWith(incoming) }
        if (existing == null) return insertShot(incoming)
        updateShot(existing.mergedWith(incoming))
        return existing.id
    }

    /**
     * Removes every stored copy of the shot the Pi keys by [timestamp] (a shot re-filed after a
     * reconnect sits in two sessions; the Pi discarded the shot, not one filing of it), then the
     * sessions left empty. An exact match: nothing with a nearby timestamp goes. Only the phone's
     * own sessions: an imported session's shots may carry the same timestamps, and the Pi's
     * deletes are not about them (plan F3, A8).
     */
    @Transaction
    open suspend fun deleteByTimestamps(timestamps: List<String>) {
        timestamps.chunked(MAX_BOUND_ARGUMENTS).forEach { chunk -> deleteShotsByTimestamp(chunk) }
        deleteEmptySessions()
    }

    /** Deletes every session of the phone's own and their shots; imported sessions stay (A8). */
    @Transaction
    open suspend fun clearAll() {
        deleteShotsOfSource(SessionEntity.SOURCE_LOCAL)
        deleteSessionsOfSource(SessionEntity.SOURCE_LOCAL)
    }

    /** Deletes every imported session and its shots: the separate "delete imported sessions". */
    @Transaction
    open suspend fun clearImported() {
        deleteShotsOfSource(SessionEntity.SOURCE_IMPORTED)
        deleteSessionsOfSource(SessionEntity.SOURCE_IMPORTED)
    }

    /**
     * Stores an imported session with its [shots] (in their given order) in one transaction:
     * [session] as given (its `source` should be [SessionEntity.SOURCE_IMPORTED]), each shot filed
     * under it as a new row, never merged with an existing one.
     */
    @Transaction
    open suspend fun insertSessionWithShots(
        session: SessionEntity,
        shots: List<ShotEntity>,
    ) {
        insertNewSession(session)
        shots.forEach { insertShot(it.copy(id = 0, sessionId = session.id)) }
    }

    @Query("UPDATE shots SET starred = :starred WHERE id = :shotId")
    abstract suspend fun setStarred(
        shotId: Long,
        starred: Boolean,
    )

    @Query("UPDATE shots SET note = :note WHERE id = :shotId")
    abstract suspend fun setNote(
        shotId: Long,
        note: String?,
    )

    @Query("UPDATE sessions SET include_in_stats = :include WHERE id = :sessionId")
    abstract suspend fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    )

    @Query("UPDATE sessions SET note = :note WHERE id = :sessionId")
    abstract suspend fun setSessionNote(
        sessionId: String,
        note: String?,
    )

    @Query("SELECT COUNT(*) FROM shots")
    abstract suspend fun shotCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSession(session: SessionEntity)

    @Insert
    protected abstract suspend fun insertNewSession(session: SessionEntity)

    @Insert
    protected abstract suspend fun insertShot(shot: ShotEntity): Long

    @Update
    protected abstract suspend fun updateShot(shot: ShotEntity)

    @Query("SELECT * FROM shots WHERE session_id = :sessionId AND shot_number = :shotNumber LIMIT 1")
    protected abstract suspend fun findByShotNumber(
        sessionId: String,
        shotNumber: Int,
    ): ShotEntity?

    @Query("SELECT * FROM shots WHERE session_id = :sessionId AND event_id = :eventId LIMIT 1")
    protected abstract suspend fun findByEventId(
        sessionId: String,
        eventId: String,
    ): ShotEntity?

    @Query("SELECT * FROM shots WHERE session_id = :sessionId AND timestamp = :timestamp ORDER BY id")
    protected abstract suspend fun findByTimestamp(
        sessionId: String,
        timestamp: String,
    ): List<ShotEntity>

    @Query(
        """
        DELETE FROM shots WHERE timestamp IN (:timestamps)
          AND session_id IN (SELECT id FROM sessions WHERE source = 'LOCAL')
        """,
    )
    protected abstract suspend fun deleteShotsByTimestamp(timestamps: List<String>)

    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT DISTINCT session_id FROM shots)")
    protected abstract suspend fun deleteEmptySessions()

    // Explicit rather than relying on the foreign key's CASCADE, so it holds with foreign keys off.
    @Query("DELETE FROM shots WHERE session_id IN (SELECT id FROM sessions WHERE source = :source)")
    protected abstract suspend fun deleteShotsOfSource(source: String)

    @Query("DELETE FROM sessions WHERE source = :source")
    protected abstract suspend fun deleteSessionsOfSource(source: String)

    private companion object {
        /** Well under SQLite's bound-parameter limit (32766 in the bundled build, 999 in old ones). */
        const val MAX_BOUND_ARGUMENTS = 500
    }
}

/** One row of [ShotHistoryDao.observeSessions]. */
data class SessionSummaryRow(
    val id: String,
    @ColumnInfo(name = "started_at") val startedAtEpochMillis: Long,
    val host: String?,
    val transport: String,
    val source: String,
    @ColumnInfo(name = "owner_name") val ownerName: String?,
    val title: String?,
    @ColumnInfo(name = "include_in_stats") val includeInStats: Boolean,
    val note: String?,
    @ColumnInfo(name = "shot_count") val shotCount: Int,
    @ColumnInfo(name = "first_shot_at") val firstShotAt: String,
    @ColumnInfo(name = "last_shot_at") val lastShotAt: String,
)
