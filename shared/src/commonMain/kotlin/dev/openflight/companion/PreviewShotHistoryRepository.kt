// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.PiLiveShot
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.TransportType
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
 */
internal class PreviewShotHistoryRepository(
    private val writeDelayMillis: Long? = DEFAULT_WRITE_DELAY_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ShotHistoryRepository {
    override val isPersistent: StateFlow<Boolean> = MutableStateFlow(true)
    override val currentSessionId: StateFlow<String?> = MutableStateFlow(CURRENT_SESSION)

    private val stored = MutableStateFlow(seed())

    override fun sessions(): Flow<List<HistorySession>> =
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
