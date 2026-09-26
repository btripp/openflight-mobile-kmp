// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.ImportedSession
import dev.openflight.companion.core.data.PiLiveShot
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The `--preview-history` launch hook's [ShotHistoryRepository] (debug builds only): two stored
 * sessions in memory, so the history screens can be shown and UI-tested without a Pi.
 *
 * - "preview-current": today's Wi-Fi session from `raspberrypi.local:8080`, the current one, with
 *   two profiles (Ann: a driver and a 7-iron; Bo: a driver).
 * - "preview-older": a one-shot Bluetooth session a few days earlier.
 *
 * Deletes and "clear all" land after [writeDelayMillis], like a real store writing in the
 * background, so their pending state can be seen; with `null` (`--preview-history-stuck`) they never
 * land, so the screens' "storage didn't respond" failure can be seen too.
 *
 * It holds no imported sessions (plan F3): imports, the stats flag and notes are no-ops here.
 *
 * With [bulkShots] > 0 (`--preview-history-bulk`, plan F8b) a third, older session,
 * [BULK_SESSION], holds that many shots across six clubs, for the range overlay's 200-shot
 * performance check.
 */
@Suppress("TooManyFunctions") // Mirrors the ShotHistoryRepository surface.
internal class PreviewShotHistoryRepository(
    private val writeDelayMillis: Long? = DEFAULT_WRITE_DELAY_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    bulkShots: Int = 0,
) : ShotHistoryRepository {
    override val isPersistent: StateFlow<Boolean> = MutableStateFlow(true)
    override val currentSessionId: StateFlow<String?> = MutableStateFlow(CURRENT_SESSION)

    private val stored = MutableStateFlow(seed() + bulkSession(bulkShots))

    override fun sessions(includeImported: Boolean): Flow<List<HistorySession>> =
        stored.map { sessions ->
            sessions
                .filter { it.shots.isNotEmpty() }
                .map { session ->
                    val timestamps = session.shots.map { it.detail.timestamp }
                    HistorySession(
                        id = session.id,
                        startedAtEpochMillis = 0L,
                        host = session.host,
                        transport = session.transport,
                        shotCount = session.shots.size,
                        firstShotAt = timestamps.min(),
                        lastShotAt = timestamps.max(),
                    )
                }.sortedByDescending { it.lastShotAt }
        }

    override fun shots(
        sessionId: String,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        stored.map { sessions ->
            sessions
                .firstOrNull { it.id == sessionId }
                ?.shots
                .orEmpty()
                .filter { profileId.isNullOrBlank() || it.detail.profileId == profileId }
        }

    override fun shotsForClub(
        club: GolfClub,
        window: ShotWindow,
        profileId: String?,
    ): Flow<List<HistoryShot>> =
        stored.map { sessions ->
            sessions.flatMap { session ->
                session.shots.filter {
                    it.detail.club == club.wireValue && (profileId.isNullOrBlank() || it.detail.profileId == profileId)
                }
            }
        }

    override fun startSession(
        host: String?,
        transport: TransportType,
    ) = Unit

    override fun record(
        shot: ShotEvent,
        detail: ShotDetail?,
    ) = Unit

    override fun record(shot: PiLiveShot) = Unit

    override fun deleteShots(timestamps: Collection<String>) =
        write {
            stored.update { sessions ->
                sessions.map { session ->
                    session.copy(
                        shots =
                            session.shots.filterNot {
                                it.detail.timestamp in
                                    timestamps
                            },
                    )
                }
            }
        }

    override fun clearAll() = write { stored.value = emptyList() }

    override fun clearImported() = Unit

    override fun setStarred(
        shotId: Long,
        starred: Boolean,
    ) = updateShots { if (it.id == shotId) it.copy(starred = starred) else it }

    override fun setNote(
        shotId: Long,
        note: String?,
    ) = updateShots { if (it.id == shotId) it.copy(note = note) else it }

    override fun setIncludeInStats(
        sessionId: String,
        include: Boolean,
    ) = Unit

    override fun setSessionNote(
        sessionId: String,
        note: String?,
    ) = Unit

    override suspend fun importSession(session: ImportedSession): String? = null

    private fun updateShots(change: (HistoryShot) -> HistoryShot) =
        stored.update { sessions -> sessions.map { session -> session.copy(shots = session.shots.map(change)) } }

    private fun write(change: () -> Unit) {
        val delayMillis = writeDelayMillis ?: return
        scope.launch {
            delay(delayMillis)
            change()
        }
    }

    private data class StoredSession(
        val id: String,
        val host: String?,
        val transport: TransportType,
        val shots: List<HistoryShot>,
    )

    companion object {
        const val CURRENT_SESSION = "preview-current"
        const val OLDER_SESSION = "preview-older"
        const val BULK_SESSION = "preview-bulk"

        /** The bulk session's row ids start here, clear of the other sessions' 1, 2, 3. */
        private const val BULK_FIRST_ID = 1_000L

        /** Long enough for a UI test to see the pending state, short enough not to slow it down. */
        const val DEFAULT_WRITE_DELAY_MILLIS = 2_000L

        @Suppress("MagicNumber") // Seed data: made-up shots, like `PreviewShotRepository.PREVIEW_SHOT`.
        private fun seed(): List<StoredSession> =
            listOf(
                StoredSession(
                    id = CURRENT_SESSION,
                    host = "raspberrypi.local:8080",
                    transport = TransportType.WIFI,
                    // Newest first, like the real store.
                    shots =
                        listOf(
                            shot(CURRENT_SESSION, 3, "2026-09-25T10:45:12.100000", "driver", 148.9, 251.0, "bo", "Bo"),
                            shot(
                                CURRENT_SESSION,
                                2,
                                "2026-09-25T10:21:40.500000",
                                "7-iron",
                                118.2,
                                165.0,
                                "ann",
                                "Ann",
                            ),
                            shot(
                                CURRENT_SESSION,
                                1,
                                "2026-09-25T10:03:35.906612",
                                "driver",
                                151.4,
                                264.0,
                                "ann",
                                "Ann",
                            ),
                        ),
                ),
                StoredSession(
                    id = OLDER_SESSION,
                    host = null,
                    transport = TransportType.BLUETOOTH,
                    shots =
                        listOf(
                            shot(OLDER_SESSION, 1, "2026-09-21T18:12:05.000000", "driver", 150.1, 258.0, "ann", "Ann"),
                        ),
                ),
            )

        /** A club's wire value, ball speed (mph) and carry (yards) for the bulk session. */
        private data class BulkClub(
            val club: String,
            val ballSpeedMph: Double,
            val carryYards: Double,
        )

        @Suppress("MagicNumber") // Seed data: made-up, plausible numbers per club.
        private val BULK_CLUBS =
            listOf(
                BulkClub("driver", 150.0, 255.0),
                BulkClub("3-wood", 140.0, 225.0),
                BulkClub("5-iron", 125.0, 185.0),
                BulkClub("7-iron", 118.0, 165.0),
                BulkClub("9-iron", 105.0, 135.0),
                BulkClub("pitching-wedge", 95.0, 115.0),
            )

        /** [count] shots, seven seconds apart on 2026-09-19, newest first, with some spread. */
        @Suppress("MagicNumber") // Seed data.
        private fun bulkSession(count: Int): List<StoredSession> {
            if (count <= 0) return emptyList()
            val shots =
                (count downTo 1).map { number ->
                    val club = BULK_CLUBS[number % BULK_CLUBS.size]
                    val spread = (number * 37 % 21 - 10).toDouble()
                    val seconds = number * 7
                    val timestamp =
                        "2026-09-19T${(9 + seconds / 3_600).toString().padStart(2, '0')}:" +
                            "${(seconds / 60 % 60).toString().padStart(2, '0')}:" +
                            "${(seconds % 60).toString().padStart(2, '0')}.000000"
                    val base =
                        shot(
                            BULK_SESSION,
                            number,
                            timestamp,
                            club.club,
                            club.ballSpeedMph + spread / 4,
                            club.carryYards + spread,
                            "ann",
                            "Ann",
                        )
                    base.copy(
                        id = BULK_FIRST_ID + number,
                        detail = base.detail.copy(launchAngleHorizontal = spread / 3),
                    )
                }
            return listOf(StoredSession(id = BULK_SESSION, host = null, transport = TransportType.WIFI, shots = shots))
        }

        @Suppress("LongParameterList", "MagicNumber")
        private fun shot(
            sessionId: String,
            number: Int,
            timestamp: String,
            club: String,
            ballSpeedMph: Double,
            carryYards: Double,
            profileId: String,
            profileName: String,
        ) = HistoryShot(
            id = number.toLong(),
            sessionId = sessionId,
            eventId = null,
            detail =
                ShotDetail(
                    timestamp = timestamp,
                    shotNumber = number,
                    ballSpeedMph = ballSpeedMph,
                    estimatedCarryYards = carryYards,
                    club = club,
                    profileId = profileId,
                    profileName = profileName,
                    launchAngleVertical = 12.6,
                    launchAngleHorizontal = if (number % 2 == 0) 1.5 else -1.3,
                    spinRpm = 2_380.0,
                ),
        )
    }
}
