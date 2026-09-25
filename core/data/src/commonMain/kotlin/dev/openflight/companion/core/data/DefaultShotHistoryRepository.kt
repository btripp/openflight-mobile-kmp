// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.SessionEntity
import dev.openflight.companion.core.database.ShotEntity
import dev.openflight.companion.core.database.ShotHistoryDao
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [ShotHistoryRepository] over the Room [ShotHistoryDao] (plan R8h).
 *
 * The database comes from [database], opened on first use with an in-memory fallback (see
 * [HistoryDatabase]); nothing here throws to the caller, the Expo app's "degrade, never throw"
 * contract.
 *
 * Writes go through one queue consumed in order on [scope], so a `shot` is always filed before
 * its `shot_update`, and a write never waits on the caller's thread.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@Suppress("TooManyFunctions") // The repository surface plus the queue helpers.
internal class DefaultShotHistoryRepository(
    private val database: HistoryDatabase,
    scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newSessionId: () -> String = { Uuid.random().toString() },
    private val log: (String) -> Unit = ::println,
) : ShotHistoryRepository {
    /** A repository with a [HistoryDatabase] of its own (tests). */
    constructor(
        openDatabase: () -> ShotHistoryDatabase,
        scope: CoroutineScope,
        fallbackDatabase: () -> ShotHistoryDatabase = {
            inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
        },
        now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        newSessionId: () -> String = { Uuid.random().toString() },
        log: (String) -> Unit = ::println,
    ) : this(HistoryDatabase(openDatabase, scope, fallbackDatabase, log), scope, now, newSessionId, log)

    override val isPersistent: StateFlow<Boolean> = database.isPersistent

    private val currentSession = MutableStateFlow<SessionEntity?>(null)
    private val mutableCurrentSessionId = MutableStateFlow<String?>(null)
    override val currentSessionId: StateFlow<String?> = mutableCurrentSessionId.asStateFlow()

    /** Each entry gets the DAO, or `null` when there is no database at all. */
    private val writes = Channel<suspend (ShotHistoryDao?) -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                val target = database.get()?.shotHistoryDao()
                guarded("write") { write(target) }
            }
        }
    }

    override fun sessions(includeImported: Boolean): Flow<List<HistorySession>> =
        database.observe { db ->
            db.shotHistoryDao().observeSessions(includeImported).map { rows -> rows.map { it.toHistorySession() } }
        }

    override fun shots(
        sessionId: String,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        database.observe { db ->
            val dao = db.shotHistoryDao()
            val rows =
                if (profileId.isNullOrBlank()) dao.observeShots(sessionId) else dao.observeShots(sessionId, profileId)
            rows.map { entities -> entities.map { it.toHistoryShot() } }
        }

    override fun shotsForClub(
        club: GolfClub,
        window: ShotWindow,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        database.observe { db ->
            db
                .shotHistoryDao()
                .observeShotsForClub(
                    club = club.wireValue,
                    profileId = profileId?.takeIf { it.isNotBlank() },
                    sinceEpochMillis = (window as? ShotWindow.Since)?.epochMillis ?: Long.MIN_VALUE,
                    sessionLimit = (window as? ShotWindow.LastSessions)?.count ?: NO_LIMIT,
                ).map { entities -> entities.map { it.toHistoryShot() } }
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

    override fun clearImported() = enqueue { it.clearImported() }

    override fun setStarred(
        shotId: Long,
        starred: Boolean,
    ) = enqueue { it.setStarred(shotId, starred) }

    override fun setNote(
        shotId: Long,
        note: String?,
    ) = enqueue { it.setNote(shotId, note) }

    override fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    ) = enqueue { it.setIncludeInStats(sessionId, include) }

    override fun setSessionNote(
        sessionId: String,
        note: String?,
    ) = enqueue { it.setSessionNote(sessionId, note) }

    override suspend fun importSession(session: ImportedSession): String? {
        val entity =
            SessionEntity(
                id = newSessionId(),
                startedAtEpochMillis = session.startedAtEpochMillis,
                host = null,
                transport = UNKNOWN,
                source = SessionEntity.SOURCE_IMPORTED,
                ownerName = session.ownerName?.takeIf { it.isNotBlank() },
                title = session.title?.takeIf { it.isNotBlank() },
                includeInStats = false,
                note = session.note,
            )
        val shots = session.shots.toImportedEntities()
        // Through the queue, so it lands in order with the other writes; the caller waits for it.
        val stored = CompletableDeferred<String?>()
        writes.trySend { dao ->
            try {
                if (dao != null) {
                    dao.insertSessionWithShots(entity, shots)
                    stored.complete(entity.id)
                }
            } finally {
                // No database, or the write failed (the queue logs it): nothing was stored.
                stored.complete(null)
            }
        }
        return stored.await()
    }

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

        /** SQLite's `LIMIT -1`: no limit. */
        const val NO_LIMIT = -1
    }
}
