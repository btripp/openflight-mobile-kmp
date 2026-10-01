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
import kotlinx.coroutines.flow.update
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
 *
 * Plan F14: with [demoWorld] this is Demo mode's history instead, over the same database: its
 * sessions are written with `source = 'DEMO'`, and every list, stat, delete and clear sees only
 * those, while the phone's own history (the default) never sees them.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@Suppress("TooManyFunctions") // The repository surface plus the queue helpers.
internal class DefaultShotHistoryRepository(
    private val database: HistoryDatabase,
    scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newSessionId: () -> String = { Uuid.random().toString() },
    private val log: (String) -> Unit = ::println,
    private val demoWorld: Boolean = false,
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

    /** The `sessions.source` this repository writes and reads (plan F14). */
    private val world: String = if (demoWorld) SessionEntity.SOURCE_DEMO else SessionEntity.SOURCE_LOCAL

    private val currentSession = MutableStateFlow<CurrentSession?>(null)

    /**
     * Sessions a continued session rolled over into ([fileShot]), by the rolled-over session's id.
     * Read and written only by the write queue's consumer.
     */
    private val rolledOver = mutableMapOf<String, CurrentSession>()
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
            db
                .shotHistoryDao()
                .observeSessionsOf(world, includeImported && !demoWorld)
                .map { rows -> rows.map { it.toHistorySession() } }
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
                .observeShotsForClubIn(
                    club = club.wireValue,
                    profileId = profileId?.takeIf { it.isNotBlank() },
                    sinceEpochMillis = (window as? ShotWindow.Since)?.epochMillis ?: Long.MIN_VALUE,
                    sessionLimit = (window as? ShotWindow.LastSessions)?.count ?: NO_LIMIT,
                    demo = demoWorld,
                ).map { entities -> entities.map { it.toHistoryShot() } }
        }

    /**
     * Tester report 2026-09-30: a reconnect to the same Pi over the same transport within
     * [SESSION_IDLE_GAP_MILLIS] of the session's last shot (or its start) continues it, so
     * switching apps, locking the screen or a Wi-Fi blip doesn't split a practice session.
     * Otherwise a new session starts.
     */
    override fun startSession(
        host: String?,
        transport: TransportType,
    ) {
        val sessionHost = host?.takeIf { transport == TransportType.WIFI }
        val time = now()
        val current = currentSession.value
        if (current != null && current.isContinuedBy(sessionHost, transport.name, time)) {
            currentSession.value = current.copy(continued = true)
            return
        }
        makeCurrent(newSession(sessionHost, transport.name, time))
    }

    override fun record(
        shot: ShotEvent,
        detail: ShotDetail?,
    ) = upsert(shot.toEntity(detail))

    override fun record(shot: PiLiveShot) = upsert(shot.toEntity())

    override fun deleteShots(timestamps: Collection<String>) {
        if (timestamps.isEmpty()) return
        val list = timestamps.toList()
        enqueue { it.deleteByTimestampsIn(list, world) }
    }

    override fun clearAll() = enqueue { if (demoWorld) it.clearDemo() else it.clearAll() }

    override fun clearImported() {
        // Imports belong to the phone's own history; Demo mode has none to delete.
        if (!demoWorld) enqueue { it.clearImported() }
    }

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

    /**
     * Plan F14 ([DemoShotHistoryRepository.seedIfEmpty]): stores [sessions] as Demo mode sessions,
     * each shot in its own row with its profile kept, unless demo sessions exist already. Through
     * the queue, like [importSession]; the caller waits for it.
     */
    internal suspend fun seedDemoIfEmpty(sessions: List<DemoSeedSession>): Boolean {
        val seeded = CompletableDeferred<Boolean>()
        writes.trySend { dao ->
            try {
                if (dao != null && dao.sessionCountOf(SessionEntity.SOURCE_DEMO) == 0) {
                    sessions.forEach { session ->
                        dao.insertSessionWithShots(
                            SessionEntity(
                                id = newSessionId(),
                                startedAtEpochMillis = session.startedAtEpochMillis,
                                host = DemoModeRepository.DEMO_HOST,
                                transport = TransportType.WIFI.name,
                                source = SessionEntity.SOURCE_DEMO,
                                title = session.title,
                            ),
                            session.shots.toDemoEntities(),
                        )
                    }
                    seeded.complete(true)
                }
            } finally {
                seeded.complete(false)
            }
        }
        return seeded.await()
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
        val filed = currentSession.value ?: startUnknownSession()
        val time = now()
        currentSession.update { if (it?.session?.id == filed.session.id) it.copy(lastActivityMillis = time) else it }
        enqueue { fileShot(it, filed, shot) }
    }

    /**
     * Runs on the write queue. A continued session ([startSession]) may be filed into by a Pi that
     * restarted during the gap and numbers its shots afresh; upserting by shot number would then
     * overwrite a stored shot. So the first shot whose number a different stored shot holds (another
     * timestamp) starts a new session, and it and every later shot of the old one are filed there.
     */
    private suspend fun fileShot(
        dao: ShotHistoryDao,
        filed: CurrentSession,
        shot: ShotEntity,
    ) {
        val target = rolledOver[filed.session.id] ?: filed
        if (!target.continued || !dao.isNumberHeldByAnotherShot(target.session.id, shot)) {
            dao.upsert(target.session, shot)
            return
        }
        val time = now()
        val next = CurrentSession(newSession(target.session.host, target.session.transport, time), false, time)
        rolledOver[filed.session.id] = next
        rolledOver[target.session.id] = next
        currentSession.update { if (it?.session?.id == target.session.id) next else it }
        if (currentSession.value?.session?.id == next.session.id) mutableCurrentSessionId.value = next.session.id
        dao.upsert(next.session, shot)
    }

    private fun startUnknownSession(): CurrentSession {
        val time = now()
        val current = CurrentSession(newSession(host = null, transport = UNKNOWN, time), false, time)
        // Two first shots racing each other must still share one session.
        if (currentSession.compareAndSet(null, current)) mutableCurrentSessionId.value = current.session.id
        return currentSession.value ?: current
    }

    private fun newSession(
        host: String?,
        transport: String,
        startedAt: Long,
    ) = SessionEntity(
        id = newSessionId(),
        startedAtEpochMillis = startedAt,
        host = host,
        transport = transport,
        source = world,
    )

    private fun makeCurrent(session: SessionEntity) {
        currentSession.value =
            CurrentSession(session, continued = false, lastActivityMillis = session.startedAtEpochMillis)
        mutableCurrentSessionId.value = session.id
    }

    /**
     * The session shots are filed under.
     *
     * @property continued a reconnect continued it ([startSession]), so [fileShot] watches for a
     *   Pi that numbers its shots afresh.
     * @property lastActivityMillis the phone's clock when it last filed a shot in it, or its start.
     */
    private data class CurrentSession(
        val session: SessionEntity,
        val continued: Boolean,
        val lastActivityMillis: Long,
    ) {
        /** A connect at [time] to the same Pi ([host]) over the same [transport], within the idle gap. */
        fun isContinuedBy(
            host: String?,
            transport: String,
            time: Long,
        ): Boolean =
            session.host == host &&
                session.transport == transport &&
                time - lastActivityMillis <= SESSION_IDLE_GAP_MILLIS
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

    internal companion object {
        /** A reconnect within this long of the session's last shot continues it ([startSession]). */
        const val SESSION_IDLE_GAP_MILLIS = 30 * 60 * 1_000L

        private const val UNKNOWN = "UNKNOWN"

        /** SQLite's `LIMIT -1`: no limit. */
        private const val NO_LIMIT = -1
    }
}
