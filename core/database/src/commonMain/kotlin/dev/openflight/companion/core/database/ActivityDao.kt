// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Activities (schema v2, plan F3), newest first. */
@Dao
abstract class ActivityDao {
    @Query("SELECT * FROM activities ORDER BY started_at DESC, id DESC")
    abstract fun observeActivities(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE type = :type ORDER BY started_at DESC, id DESC")
    abstract fun observeActivities(type: String): Flow<List<ActivityEntity>>

    /**
     * Inserts or replaces [activity] by id. Its `session_id` is dropped when that session isn't
     * stored (a game played before its first shot was filed), rather than failing the write.
     */
    @Transaction
    open suspend fun upsert(activity: ActivityEntity) {
        val sessionId = activity.sessionId?.takeIf { sessionExists(it) }
        insertOrReplace(activity.copy(sessionId = sessionId))
    }

    @Query("DELETE FROM activities WHERE id = :id")
    abstract suspend fun delete(id: String)

    // REPLACE (delete + insert) is safe since nothing references an activity row. Room's @Upsert
    // threw on its update path under the Android host tests' bundled driver.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOrReplace(activity: ActivityEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM sessions WHERE id = :sessionId)")
    protected abstract suspend fun sessionExists(sessionId: String): Boolean
}
