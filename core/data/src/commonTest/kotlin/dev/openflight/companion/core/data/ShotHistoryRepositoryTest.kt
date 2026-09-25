// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.single
import assertk.assertions.startsWith
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [DefaultShotHistoryRepository] over a real in-memory Room database (plan R8h). The Expo
 * `shotRepository.test.ts` storage cases live in `core:database`'s `ShotHistoryDaoTest`; these
 * cover what the repository adds: sessions per connect, the model mapping, and the
 * "unavailable database" contract.
 */
class ShotHistoryRepositoryTest {
    private class Harness(
        scope: TestScope,
        openDatabase: () -> ShotHistoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase,
    ) {
        val logs = mutableListOf<String>()
        private var sessionCount = 0
        val repository =
            DefaultShotHistoryRepository(
                openDatabase = openDatabase,
                scope = scope.backgroundScope,
                fallbackDatabase = fallbackDatabase,
                now = { 1_000L * sessionCount },
                newSessionId = { "session-${++sessionCount}" },
                log = { logs += it },
            )
    }

    private fun runHistoryTest(
        openDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        body(Harness(this, openDatabase, fallbackDatabase))
    }

    @Test
    fun everyConnectStartsANewSessionListedNewestFirst() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local:8080", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T09:00:00"))
            h.repository.startSession("pi.local:8080", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00"))
            h.repository.record(liveShot(2, "2026-09-14T10:05:00"))
            h.repository.awaitWrites()

            val sessions = h.repository.sessions().first()
            assertThat(sessions.map { it.id }).containsExactly("session-2", "session-1")
            assertThat(sessions[0]).isEqualTo(
                HistorySession(
                    id = "session-2",
                    startedAtEpochMillis = 2_000L,
                    host = "pi.local:8080",
                    transport = TransportType.WIFI,
                    shotCount = 2,
                    firstShotAt = "2026-09-14T10:00:00",
                    lastShotAt = "2026-09-14T10:05:00",
                ),
            )
            assertThat(h.repository.currentSessionId.value).isEqualTo("session-2")
        }

    @Test
    fun aBluetoothSessionKeepsNoHost() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local:8080", TransportType.BLUETOOTH)
            h.repository.record(shot(1))
            h.repository.awaitWrites()

            assertThat(h.repository.sessions().first())
                .single()
                .transform { it.host to it.transport }
                .isEqualTo(null to TransportType.BLUETOOTH)
        }

    @Test
    fun aShotBeforeAnyConnectIsFiledUnderASessionOfItsOwn() =
        runHistoryTest { h ->
            h.repository.record(shot(1))
            h.repository.record(shot(2))
            h.repository.awaitWrites()

            val session =
                h.repository
                    .sessions()
                    .first()
                    .single()
            assertThat(session.shotCount).isEqualTo(2)
            assertThat(session.transport).isNull()
        }

    @Test
    fun aShotAndItsShotUpdateFileOneRowWithTheEnrichedValues() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(7, "2026-09-14T10:00:00", spinRpm = null))
            h.repository.record(liveShot(7, "2026-09-14T10:00:00", spinRpm = 2680.0))
            h.repository.awaitWrites()

            val shots = h.repository.shots("session-1").first()
            assertThat(shots.map { it.detail.spinRpm }).containsExactly(2680.0)
        }

    @Test
    fun theSseEventAndItsSocketIoDetailFileOneRow() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            val event = shot(1).copy(timestamp = "2026-09-14T10:00:00.5")
            h.repository.record(event)
            h.repository.record(liveShot(4, "2026-09-14T10:00:00.5", profileId = "p1"))
            h.repository.awaitWrites()

            val stored =
                h.repository
                    .shots("session-1")
                    .first()
                    .single()
            assertThat(stored.eventId).isEqualTo(event.eventId)
            assertThat(stored.detail.shotNumber).isEqualTo(4)
            assertThat(stored.detail.profileId).isEqualTo("p1")
        }

    @Test
    fun anSseShotWithAKnownDetailIsStoredWithTheDetailsFields() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            val event = shot(1).copy(timestamp = "2026-09-14T10:00:00", launchAngleVertical = 11.0)
            val known = detail(3, "2026-09-14T10:00:00", profileId = "p1").copy(carryRange = listOf(170.0, 188.0))
            h.repository.record(event, known)
            h.repository.awaitWrites()

            val stored =
                h.repository
                    .shots("session-1")
                    .first()
                    .single()
            assertThat(stored.eventId).isEqualTo(event.eventId)
            assertThat(stored.detail.shotNumber).isEqualTo(3)
            assertThat(stored.detail.carryRange).isEqualTo(listOf(170.0, 188.0))
            // The Socket.IO detail wins where both carry a value; the event fills what it lacks.
            assertThat(stored.detail.ballSpeedMph).isEqualTo(116.2)
            assertThat(stored.detail.launchAngleVertical).isEqualTo(11.0)
        }

    // Expo: "treats an unset profile as absent rather than as a player with no name"
    @Test
    fun treatsAnUnsetProfileAsAbsentRatherThanAPlayerWithNoName() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00", profileId = "", profileName = ""))
            h.repository.awaitWrites()

            val stored =
                h.repository
                    .shots("session-1")
                    .first()
                    .single()
                    .detail
            assertThat(stored.profileId).isNull()
            assertThat(stored.profileName).isNull()
        }

    @Test
    fun shotsCanBeFilteredToOneProfile() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00", profileId = "p1"))
            h.repository.record(liveShot(2, "2026-09-14T10:01:00", profileId = "p2"))
            h.repository.awaitWrites()

            assertThat(
                h.repository
                    .shots("session-1", profileId = "p1")
                    .first()
                    .map { it.detail.shotNumber },
            ).containsExactly(1)
            assertThat(h.repository.shots("session-1", profileId = "").first()).hasSize(2)
        }

    @Test
    fun deletesAndClearAllReachObservers() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.shots("session-1").test {
                assertThat(awaitItem()).isEmpty()

                h.repository.record(liveShot(1, "2026-09-14T10:00:00"))
                h.repository.record(liveShot(2, "2026-09-14T10:05:00"))
                assertThat(awaitTimestamps { it.size == 2 })
                    .containsExactly("2026-09-14T10:05:00", "2026-09-14T10:00:00")

                h.repository.deleteShot("2026-09-14T10:00:00")
                assertThat(awaitTimestamps { it.size == 1 }).containsExactly("2026-09-14T10:05:00")

                h.repository.clearAll()
                assertThat(awaitTimestamps { it.isEmpty() }).isEmpty()
            }
        }

    @Test
    fun deleteShotsRemovesEveryListedShot() =
        runHistoryTest { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00"))
            h.repository.record(liveShot(2, "2026-09-14T10:01:00"))
            h.repository.record(liveShot(3, "2026-09-14T10:02:00"))

            h.repository.deleteShots(listOf("2026-09-14T10:00:00", "2026-09-14T10:02:00"))
            h.repository.awaitWrites()

            assertThat(
                h.repository
                    .shots("session-1")
                    .first()
                    .map { it.detail.shotNumber },
            ).containsExactly(2)
        }

    // Expo: "degrades to an empty history when the database is unavailable", plus the plan's
    // in-memory fallback: this launch's shots are still kept.
    @Test
    fun anUnavailableDatabaseLogsOnceAndKeepsThisLaunchsHistoryInMemory() =
        runHistoryTest(openDatabase = { error("disk I/O error") }) { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00"))
            h.repository.record(liveShot(2, "2026-09-14T10:01:00"))
            h.repository.awaitWrites()

            assertThat(h.repository.isPersistent.value).isFalse()
            assertThat(h.logs).single().startsWith("Shot history database unavailable (disk I/O error)")
            assertThat(
                h.repository
                    .sessions()
                    .first()
                    .single()
                    .shotCount,
            ).isEqualTo(2)
        }

    @Test
    fun withNoDatabaseAtAllReadsAreEmptyAndWritesAreDropped() =
        runHistoryTest(openDatabase = { error("disk I/O error") }, fallbackDatabase = { error("no memory") }) { h ->
            h.repository.startSession("pi.local", TransportType.WIFI)
            h.repository.record(liveShot(1, "2026-09-14T10:00:00"))
            h.repository.deleteShot("2026-09-14T10:00:00")
            h.repository.clearAll()
            h.repository.awaitWrites()

            assertThat(h.repository.sessions().first()).isEmpty()
            assertThat(h.repository.shots("session-1").first()).isEmpty()
            assertThat(h.logs).hasSize(2)
        }

    @Test
    fun aHealthyDatabaseIsPersistentAndLogsNothing() =
        runHistoryTest { h ->
            h.repository.record(shot(1))
            h.repository.awaitWrites()

            assertThat(h.repository.isPersistent.value).isTrue()
            assertThat(h.logs).isEmpty()
        }

    private suspend fun app.cash.turbine.ReceiveTurbine<List<HistoryShot>>.awaitTimestamps(
        until: (List<HistoryShot>) -> Boolean,
    ): List<String> {
        var item = awaitItem()
        while (!until(item)) item = awaitItem()
        return item.map { it.detail.timestamp }
    }

    private companion object {
        fun inMemoryDatabase(): ShotHistoryDatabase = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()

        fun detail(
            number: Int?,
            timestamp: String,
            spinRpm: Double? = 2593.0,
            profileId: String? = null,
            profileName: String? = null,
        ) = ShotDetail(
            timestamp = timestamp,
            shotNumber = number,
            ballSpeedMph = 116.2,
            clubSpeedMph = 78.6,
            smashFactor = 1.48,
            estimatedCarryYards = 179.0,
            club = "driver",
            profileId = profileId,
            profileName = profileName,
            spinRpm = spinRpm,
        )

        fun liveShot(
            number: Int?,
            timestamp: String,
            spinRpm: Double? = 2593.0,
            profileId: String? = null,
            profileName: String? = null,
        ) = PiLiveShot(detail(number, timestamp, spinRpm, profileId, profileName), rawJson = null)
    }
}
