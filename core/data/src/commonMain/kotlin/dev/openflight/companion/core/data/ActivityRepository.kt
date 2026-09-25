// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.ActivityDao
import dev.openflight.companion.core.database.ActivityEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Finished games and activities (plan F3), stored with the shot history: what the Activities
 * history shows. The owning feature decides the [Activity.type] names and the JSON payloads.
 *
 * Nothing here throws: without a database reads are empty and writes do nothing (logged).
 */
interface ActivityRepository {
    /** Activities newest first, optionally only those of [type]. */
    fun activities(type: String? = null): Flow<List<Activity>>

    /**
     * Stores [activity], replacing a stored one with the same id. A [Activity.sessionId] naming a
     * session that isn't stored is dropped.
     */
    suspend fun record(activity: Activity)

    suspend fun delete(id: String)
}

/**
 * One finished activity.
 *
 * @property type the activity kind, e.g. `"TARGET_CALLOUT"` (the owning feature's names).
 * @property headline the card's big text, e.g. `"4.2 yd"` or `"You won!"`.
 * @property playersJson/resultJson opaque JSON the owning feature writes and reads.
 * @property sessionId the history session its shots were filed under, while that session exists.
 */
data class Activity(
    val id: String,
    val type: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val title: String,
    val headline: String,
    val playersJson: String,
    val resultJson: String,
    val sessionId: String? = null,
)

/** [ActivityRepository] over the Room [ActivityDao] in the shared [HistoryDatabase] (plan F3). */
internal class DefaultActivityRepository(
    private val database: HistoryDatabase,
    private val log: (String) -> Unit = ::println,
) : ActivityRepository {
    override fun activities(type: String?): Flow<List<Activity>> =
        database
            .observe { db ->
                if (type == null) db.activityDao().observeActivities() else db.activityDao().observeActivities(type)
            }.map { rows -> rows.map { it.toActivity() } }

    override suspend fun record(activity: Activity) = write("record") { it.upsert(activity.toEntity()) }

    override suspend fun delete(id: String) = write("delete") { it.delete(id) }

    @Suppress("TooGenericExceptionCaught") // A failed activity write is logged, never a crash.
    private suspend fun write(
        what: String,
        block: suspend (ActivityDao) -> Unit,
    ) {
        val dao = database.get()?.activityDao() ?: return
        try {
            block(dao)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("Activity $what failed: ${error.message ?: error}")
        }
    }

    private companion object {
        fun ActivityEntity.toActivity() =
            Activity(
                id = id,
                type = type,
                startedAtEpochMillis = startedAtEpochMillis,
                endedAtEpochMillis = endedAtEpochMillis,
                title = title,
                headline = headline,
                playersJson = playersJson,
                resultJson = resultJson,
                sessionId = sessionId,
            )

        fun Activity.toEntity() =
            ActivityEntity(
                id = id,
                type = type,
                startedAtEpochMillis = startedAtEpochMillis,
                endedAtEpochMillis = endedAtEpochMillis,
                title = title,
                headline = headline,
                playersJson = playersJson,
                resultJson = resultJson,
                sessionId = sessionId,
            )
    }
}
