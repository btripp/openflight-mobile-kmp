// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Opens the on-disk history database at the platform's path (plan R8h; bound per platform in Koin). */
internal fun interface ShotHistoryDatabaseOpener {
    fun open(): ShotHistoryDatabase
}

/**
 * The one Room database behind every history repository (shots, bags, activities), opened on
 * first use, off the caller's thread (plan R8h, shared since F3).
 *
 * If [openDatabase] fails (or its first query does), the failure is logged once and an in-memory
 * database from [fallbackDatabase] takes its place for this launch ([isPersistent] turns `false`);
 * if that fails too, [get] returns `null`: reads are empty and writes are dropped. Nothing here
 * throws to the caller, the Expo app's "degrade, never throw" contract.
 */
internal class HistoryDatabase(
    private val openDatabase: () -> ShotHistoryDatabase,
    scope: CoroutineScope,
    private val fallbackDatabase: () -> ShotHistoryDatabase = {
        inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
    },
    private val log: (String) -> Unit = ::println,
) {
    private val mutableIsPersistent = MutableStateFlow(true)
    val isPersistent: StateFlow<Boolean> = mutableIsPersistent.asStateFlow()

    private val database: Deferred<ShotHistoryDatabase?> = scope.async(start = CoroutineStart.LAZY) { open() }

    /** The database, or `null` when there is none at all. */
    suspend fun get(): ShotHistoryDatabase? = database.await()

    /** [query]'s flow, or an empty list when there is no database or a read fails (logged). */
    fun <T> observe(query: (ShotHistoryDatabase) -> Flow<List<T>>): Flow<List<T>> =
        flow {
            val target = get()
            if (target == null) emit(emptyList()) else emitAll(query(target))
        }.catch { error ->
            log("Shot history read failed: ${error.message ?: error}")
            emit(emptyList())
        }

    /** Opens the database and proves it with a query; on failure logs once and falls back. */
    private suspend fun open(): ShotHistoryDatabase? {
        val primary = openAndProbe(openDatabase)
        if (primary.isSuccess) return primary.getOrThrow()
        mutableIsPersistent.value = false
        log(
            "Shot history database unavailable (${primary.exceptionOrNull()?.message}); " +
                "keeping this launch's history in memory.",
        )
        return openAndProbe(fallbackDatabase).getOrElse { error ->
            log("In-memory shot history unavailable too (${error.message}); history is off.")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure to open means "no database", never a crash.
    private suspend fun openAndProbe(factory: () -> ShotHistoryDatabase): Result<ShotHistoryDatabase> =
        try {
            val database = factory()
            database.shotHistoryDao().shotCount()
            Result.success(database)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
