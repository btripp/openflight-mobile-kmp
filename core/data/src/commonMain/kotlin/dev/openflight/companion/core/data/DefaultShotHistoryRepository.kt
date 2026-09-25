// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.SessionEntity
import dev.openflight.companion.core.database.ShotEntity
import dev.openflight.companion.core.database.ShotHistoryDao
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Opens the on-disk history database at the platform's path (plan R8h; bound per platform in Koin). */
internal fun interface ShotHistoryDatabaseOpener {
    fun open(): ShotHistoryDatabase
}

/**
 * [ShotHistoryRepository] over the Room [ShotHistoryDao] (plan R8h).
 *
 * The database is opened on first use, off the caller's thread. If [openDatabase] fails (or its
 * first query does), the failure is logged once and an in-memory database from [fallbackDatabase]
 * takes its place for this launch; if that fails too, reads are empty and writes are dropped.
 * Nothing here throws to the caller, the Expo app's "degrade, never throw" contract.
 *
 * Writes go through one queue consumed in order on [scope], so a `shot` is always filed before
 * its `shot_update`, and a write never waits on the caller's thread.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@Suppress("TooManyFunctions") // The repository surface plus the queue and open helpers.
internal class DefaultShotHistoryRepository(
    private val openDatabase: () -> ShotHistoryDatabase,
    private val scope: CoroutineScope,
    private val fallbackDatabase: () -> ShotHistoryDatabase = {
        inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
    },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newSessionId: () -> String = { Uuid.random().toString() },
    private val log: (String) -> Unit = ::println,
) : ShotHistoryRepository {
    private val mutableIsPersistent = MutableStateFlow(true)
    override val isPersistent: StateFlow<Boolean> = mutableIsPersistent.asStateFlow()

    private val currentSession = MutableStateFlow<SessionEntity?>(null)
    private val mutableCurrentSessionId = MutableStateFlow<String?>(null)
    override val currentSessionId: StateFlow<String?> = mutableCurrentSessionId.asStateFlow()

    private val dao: Deferred<ShotHistoryDao?> = scope.async(start = CoroutineStart.LAZY) { open() }

    /** Each entry gets the DAO, or `null` when there is no database at all. */
    private val writes = Channel<suspend (ShotHistoryDao?) -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                val target = dao.await()
                guarded("write") { write(target) }
            }
        }
    }

    override fun sessions(): Flow<List<HistorySession>> =
        observe { dao -> dao.observeSessions().map { rows -> rows.map { it.toHistorySession() } } }

    override fun shots(
        sessionId: String,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        observe { dao ->
            val rows =
                if (profileId.isNullOrBlank()) dao.observeShots(sessionId) else dao.observeShots(sessionId, profileId)
            rows.map { entities -> entities.map { it.toHistoryShot() } }
        }

    override fun startSession(
        host: String?,
        transport: TransportType,
    ) {
        val session =
            SessionEntity(
                id = newSessionId(),
                startedAtEpochMillis = now(),
                host = host?.takeIf { transport == TransportType.WIFI },
                transport = transport.name,
            )
        currentSession.value = session
        mutableCurrentSessionId.value = session.id
    }

    override fun record(
        shot: ShotEvent,
        detail: ShotDetail?,
    ) = upsert(shot.toEntity(detail))

    override fun record(shot: PiLiveShot) = upsert(shot.toEntity())

    override fun deleteShots(timestamps: Collection<String>) {
        if (timestamps.isEmpty()) return
        val list = timestamps.toList()
        enqueue { it.deleteByTimestamps(list) }
    }

    override fun clearAll() = enqueue { it.clearAll() }

    /** Suspends until every write queued so far has been applied (tests). */
    internal suspend fun awaitWrites() {
        val done = CompletableDeferred<Unit>()
        writes.trySend { done.complete(Unit) }
        done.await()
    }

    private fun upsert(shot: ShotEntity) {
        // Captured now, so a shot is filed under the session it arrived in even if a reconnect
        // starts the next one before the write runs. A shot before any connect gets its own session.
        val session = currentSession.value ?: startUnknownSession()
        enqueue { it.upsert(session, shot) }
    }

    private fun startUnknownSession(): SessionEntity {
        val session = SessionEntity(id = newSessionId(), startedAtEpochMillis = now(), host = null, transport = UNKNOWN)
        // Two first shots racing each other must still share one session.
        if (currentSession.compareAndSet(null, session)) mutableCurrentSessionId.value = session.id
        return currentSession.value ?: session
    }

    /** Queues [write]; it is dropped when there is no database. */
    private fun enqueue(write: suspend (ShotHistoryDao) -> Unit) {
        writes.trySend { target -> if (target != null) write(target) }
    }

    private fun <T> observe(query: (ShotHistoryDao) -> Flow<List<T>>): Flow<List<T>> =
        flow {
            val target = dao.await()
            if (target == null) emit(emptyList()) else emitAll(query(target))
        }.catch { error ->
            log("Shot history read failed: ${error.message ?: error}")
            emit(emptyList())
        }

    /** Opens the database and proves it with a query; on failure logs once and falls back. */
    private suspend fun open(): ShotHistoryDao? {
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
    private suspend fun openAndProbe(factory: () -> ShotHistoryDatabase): Result<ShotHistoryDao> =
        try {
            val dao = factory().shotHistoryDao()
            dao.shotCount()
            Result.success(dao)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }

    @Suppress("TooGenericExceptionCaught") // A failed write costs history, never the live shot.
    private suspend fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("Shot history $what failed: ${error.message ?: error}")
        }
    }

    private companion object {
        const val UNKNOWN = "UNKNOWN"
    }
}
