// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.PiLiveShot
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.TransportType
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
 * `core:database` and `core:data`).
 */
class FakeShotHistoryRepository : ShotHistoryRepository {
    override val isPersistent = MutableStateFlow(true)
    override val currentSessionId = MutableStateFlow<String?>(null)

    private val sessionsById = MutableStateFlow<Map<String, SessionData>>(emptyMap())

    val deletedTimestamps = mutableListOf<String>()
    var clearAllCount = 0
        private set

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
    ) {
        sessionsById.update { it + (sessionId to SessionData(startedAtEpochMillis, host, transport, shots)) }
    }

    override fun sessions(): Flow<List<HistorySession>> =
        sessionsById.map { sessions ->
            sessions
                .filterValues { it.shots.isNotEmpty() }
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
                    )
                }.sortedByDescending { it.lastShotAt }
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
                    data.copy(shots = data.shots.filterNot { it.detail.timestamp in timestamps })
                }
            }
        }
    }

    override fun clearAll() {
        clearAllCount++
        write { sessionsById.value = emptyMap() }
    }

    private data class SessionData(
        val startedAtEpochMillis: Long,
        val host: String?,
        val transport: TransportType?,
        val shots: List<HistoryShot>,
    )
}
