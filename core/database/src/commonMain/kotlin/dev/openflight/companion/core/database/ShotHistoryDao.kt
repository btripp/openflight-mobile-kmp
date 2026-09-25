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
     */
    @Query(
        """
        SELECT s.id AS id, s.started_at AS started_at, s.host AS host, s.transport AS transport,
               COUNT(sh.id) AS shot_count,
               MIN(sh.timestamp) AS first_shot_at,
               MAX(sh.timestamp) AS last_shot_at
        FROM sessions s
        INNER JOIN shots sh ON sh.session_id = s.id
        GROUP BY s.id
        ORDER BY last_shot_at DESC, s.started_at DESC
        """,
    )
    abstract fun observeSessions(): Flow<List<SessionSummaryRow>>

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
     * sessions left empty. An exact match: nothing with a nearby timestamp goes.
     */
    @Transaction
    open suspend fun deleteByTimestamps(timestamps: List<String>) {
        timestamps.chunked(MAX_BOUND_ARGUMENTS).forEach { chunk -> deleteShotsByTimestamp(chunk) }
        deleteEmptySessions()
    }

    /** Deletes every session and shot. */
    @Transaction
    open suspend fun clearAll() {
        deleteAllShots()
        deleteAllSessions()
    }

    @Query("SELECT COUNT(*) FROM shots")
    abstract suspend fun shotCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSession(session: SessionEntity)

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

    @Query("DELETE FROM shots WHERE timestamp IN (:timestamps)")
    protected abstract suspend fun deleteShotsByTimestamp(timestamps: List<String>)

    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT DISTINCT session_id FROM shots)")
    protected abstract suspend fun deleteEmptySessions()

    @Query("DELETE FROM shots")
    protected abstract suspend fun deleteAllShots()

    @Query("DELETE FROM sessions")
    protected abstract suspend fun deleteAllSessions()

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
    @ColumnInfo(name = "shot_count") val shotCount: Int,
    @ColumnInfo(name = "first_shot_at") val firstShotAt: String,
    @ColumnInfo(name = "last_shot_at") val lastShotAt: String,
)
