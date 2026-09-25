// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.ImportedSession
import dev.openflight.companion.core.data.PiLiveShot
import dev.openflight.companion.core.data.SessionSource
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [ShotHistoryRepository] for ViewModel tests (plan R8h). Tests seed it with [put];
 * the write calls are recorded rather than merged (the real upsert rules are tested in
 * `core:database` and `core:data`). Schema v2 (plan F3): imported sessions are seeded with
 * `put(..., source = SessionSource.IMPORTED)` or [importSession]; deletes and [clearAll] leave
 * them alone, like the real store.
 */
@Suppress("TooManyFunctions") // Mirrors the ShotHistoryRepository surface.
class FakeShotHistoryRepository : ShotHistoryRepository {
    override val isPersistent = MutableStateFlow(true)
    override val currentSessionId = MutableStateFlow<String?>(null)

    private val sessionsById = MutableStateFlow<Map<String, SessionData>>(emptyMap())

    val deletedTimestamps = mutableListOf<String>()
    var clearAllCount = 0
        private set
    var clearImportedCount = 0
        private set
    private var importCount = 0

    /**
     * `false` holds back [deleteShots] and [clearAll] (still counted) until [applyPendingWrites], like
     * a store that hasn't written yet, so a test can see the pending state or a store that never
     * answers.
     */
    var applyWrites = true
    private val pendingWrites = mutableListOf<() -> Unit>()

    /** Applies the writes held back while [applyWrites] was `false`. */
    fun applyPendingWrites() {
        val writes = pendingWrites.toList()
        pendingWrites.clear()
        writes.forEach { it() }
    }

    private fun write(change: () -> Unit) {
        if (applyWrites) change() else pendingWrites += change
    }

    /** Adds (or replaces) a session with [shots] (newest first). */
    fun put(
        sessionId: String,
        shots: List<HistoryShot>,
        startedAtEpochMillis: Long = 0L,
        host: String? = "pi.local:8080",
        transport: TransportType? = TransportType.WIFI,
        source: SessionSource = SessionSource.LOCAL,
        ownerName: String? = null,
        includeInStats: Boolean = source == SessionSource.LOCAL,
    ) {
        sessionsById.update {
            it +
                (
                    sessionId to
                        SessionData(startedAtEpochMillis, host, transport, shots, source, ownerName, includeInStats)
                )
        }
    }

    override fun sessions(includeImported: Boolean): Flow<List<HistorySession>> =
        sessionsById.map { sessions ->
            sessions
                .filterValues { it.shots.isNotEmpty() && (includeImported || it.source == SessionSource.LOCAL) }
                .map { (id, data) ->
                    val timestamps = data.shots.map { it.detail.timestamp }
                    HistorySession(
                        id = id,
                        startedAtEpochMillis = data.startedAtEpochMillis,
                        host = data.host,
                        transport = data.transport,
                        shotCount = data.shots.size,
                        firstShotAt = timestamps.min(),
                        lastShotAt = timestamps.max(),
                        source = data.source,
                        ownerName = data.ownerName,
                        title = data.title,
                        includeInStats = data.includeInStats,
                        note = data.note,
                    )
                }.sortedByDescending { it.lastShotAt }
        }

    override fun shotsForClub(
        club: GolfClub,
        window: ShotWindow,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        sessionsById.map { sessions ->
            val matching =
                sessions
                    .filterValues { data ->
                        data.includeInStats &&
                            (window !is ShotWindow.Since || data.startedAtEpochMillis >= window.epochMillis)
                    }.mapValues { (_, data) ->
                        data.shots.filter {
                            it.detail.club == club.wireValue &&
                                (profileId.isNullOrBlank() || it.detail.profileId == profileId)
                        }
                    }.filterValues { it.isNotEmpty() }
                    .entries
                    .sortedByDescending { sessions.getValue(it.key).startedAtEpochMillis }
            val limited = if (window is ShotWindow.LastSessions) matching.take(window.count) else matching
            limited.flatMap { it.value }
        }

    override fun shots(
        sessionId: String,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        sessionsById.map { sessions ->
            sessions[sessionId]
                ?.shots
                .orEmpty()
                .filter { profileId.isNullOrBlank() || it.detail.profileId == profileId }
        }

    override fun startSession(
        host: String?,
        transport: TransportType,
    ) {
        currentSessionId.value = "session-${sessionsById.value.size + 1}"
    }

    override fun record(
        shot: ShotEvent,
        detail: ShotDetail?,
    ) = Unit

    override fun record(shot: PiLiveShot) = Unit

    override fun deleteShots(timestamps: Collection<String>) {
        deletedTimestamps += timestamps
        write {
            sessionsById.update { sessions ->
                sessions.mapValues { (_, data) ->
                    if (data.source != SessionSource.LOCAL) return@mapValues data
                    data.copy(shots = data.shots.filterNot { it.detail.timestamp in timestamps })
                }
            }
        }
    }

    override fun clearAll() {
        clearAllCount++
        write { sessionsById.update { sessions -> sessions.filterValues { it.source != SessionSource.LOCAL } } }
    }

    override fun clearImported() {
        clearImportedCount++
        write { sessionsById.update { sessions -> sessions.filterValues { it.source != SessionSource.IMPORTED } } }
    }

    override fun setStarred(
        shotId: Long,
        starred: Boolean,
    ) = updateShot(shotId) { it.copy(starred = starred) }

    override fun setNote(
        shotId: Long,
        note: String?,
    ) = updateShot(shotId) { it.copy(note = note) }

    override fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    ) = updateSession(sessionId) { it.copy(includeInStats = include) }

    override fun setSessionNote(
        sessionId: String,
        note: String?,
    ) = updateSession(sessionId) { it.copy(note = note) }

    /** Stores [session] as `imported-N`, with its shots newest first and row ids from 1_000_000. */
    override suspend fun importSession(session: ImportedSession): String {
        val id = "imported-${++importCount}"
        val shots =
            session.shots
                .mapIndexed { index, detail ->
                    HistoryShot(
                        id = IMPORTED_ROW_ID_BASE * importCount + index,
                        sessionId = id,
                        eventId = null,
                        detail = detail.copy(profileId = null, profileName = null),
                    )
                }.sortedByDescending { it.detail.timestamp }
        sessionsById.update {
            it +
                (
                    id to
                        SessionData(
                            startedAtEpochMillis = session.startedAtEpochMillis,
                            host = null,
                            transport = null,
                            shots = shots,
                            source = SessionSource.IMPORTED,
                            ownerName = session.ownerName,
                            includeInStats = false,
                            title = session.title,
                            note = session.note,
                        )
                )
        }
        return id
    }

    private fun updateShot(
        shotId: Long,
        change: (HistoryShot) -> HistoryShot,
    ) = sessionsById.update { sessions ->
        sessions.mapValues { (_, data) ->
            data.copy(
                shots =
                    data.shots.map {
                        if (it.id ==
                            shotId
                        ) {
                            change(it)
                        } else {
                            it
                        }
                    },
            )
        }
    }

    private fun updateSession(
        sessionId: String,
        change: (SessionData) -> SessionData,
    ) = sessionsById.update { sessions ->
        val data = sessions[sessionId] ?: return@update sessions
        sessions + (sessionId to change(data))
    }

    private data class SessionData(
        val startedAtEpochMillis: Long,
        val host: String?,
        val transport: TransportType?,
        val shots: List<HistoryShot>,
        val source: SessionSource = SessionSource.LOCAL,
        val ownerName: String? = null,
        val includeInStats: Boolean = true,
        val title: String? = null,
        val note: String? = null,
    )

    private companion object {
        const val IMPORTED_ROW_ID_BASE = 1_000_000L
    }
}
