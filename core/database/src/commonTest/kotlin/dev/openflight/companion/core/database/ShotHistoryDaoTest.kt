// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The DAO against a real (in-memory, bundled-driver) Room database, so the schema, indexes and
 * every query actually run. Cases ported from the Expo app's `__tests__/shotRepository.test.ts`
 * (main `9b5de01` plus `feat/delete-shot` `e525d90`); the names say which.
 */
class ShotHistoryDaoTest {
    private lateinit var database: ShotHistoryDatabase
    private lateinit var dao: ShotHistoryDao

    @BeforeTest
    fun setUp() {
        database = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
        dao = database.shotHistoryDao()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    // Expo: "reads back a shot exactly as it was recorded"
    @Test
    fun readsBackAShotExactlyAsItWasRecorded() =
        runTest {
            val stored = fullShot()

            val id = dao.upsert(session("session-1"), stored)

            assertThat(dao.observeShots("session-1").first()).containsExactly(
                stored.copy(id = id, sessionId = "session-1"),
            )
        }

    // Expo: "keeps a missing measurement null instead of turning it into zero"
    @Test
    fun keepsAMissingMeasurementNullInsteadOfZero() =
        runTest {
            dao.upsert(
                session("session-1"),
                fullShot().copy(clubSpeedMph = null, smashFactor = null, spinRpm = null, spinQuality = null),
            )

            val stored = dao.observeShots("session-1").first().single()
            assertThat(stored.clubSpeedMph).isNull()
            assertThat(stored.smashFactor).isNull()
            assertThat(stored.spinRpm).isNull()
            assertThat(stored.spinQuality).isNull()
        }

    // Expo: "returns a session newest-first, the order every screen wants"
    @Test
    fun returnsASessionNewestFirst() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 1, timestamp = "2026-09-14T10:00:00Z"))
            dao.upsert(session("session-1"), fullShot(shotNumber = 2, timestamp = "2026-09-14T10:05:00Z"))
            dao.upsert(session("session-1"), fullShot(shotNumber = 3, timestamp = "2026-09-14T10:02:00Z"))

            assertThat(dao.observeShots("session-1").first().map { it.timestamp }).containsExactly(
                "2026-09-14T10:05:00Z",
                "2026-09-14T10:02:00Z",
                "2026-09-14T10:00:00Z",
            )
        }

    // Expo: "keeps one player's shots out of another's history"
    @Test
    fun keepsOnePlayersShotsOutOfAnothersHistory() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 1, profileId = "p1", profileName = "Alex"))
            dao.upsert(session("session-1"), fullShot(shotNumber = 2, profileId = "p2", profileName = "Sam"))

            assertThat(
                dao
                    .observeSessions()
                    .first()
                    .first()
                    .shotCount,
            ).isEqualTo(2)
            assertThat(dao.observeShots("session-1", profileId = "p1").first()).hasSize(1)
        }

    // Expo: "stores the player the server attributed the shot to"
    @Test
    fun storesThePlayerTheServerAttributedTheShotTo() =
        runTest {
            dao.upsert(session("session-1"), fullShot(profileId = "p1", profileName = "Alex"))

            val stored = dao.observeShots("session-1").first().single()
            assertThat(stored.profileId).isEqualTo("p1")
            assertThat(stored.profileName).isEqualTo("Alex")
        }

    // Expo: "lists past sessions newest-first with their shot counts"
    @Test
    fun listsPastSessionsNewestFirstWithTheirShotCounts() =
        runTest {
            dao.upsert(session("older"), fullShot(shotNumber = 1, timestamp = "2026-09-13T09:00:00Z"))
            dao.upsert(session("newer"), fullShot(shotNumber = 1, timestamp = "2026-09-14T09:00:00Z"))
            dao.upsert(session("newer"), fullShot(shotNumber = 2, timestamp = "2026-09-14T09:30:00Z"))

            val sessions = dao.observeSessions().first()
            assertThat(sessions.map { it.id }).containsExactly("newer", "older")
            assertThat(sessions[0].shotCount).isEqualTo(2)
            assertThat(sessions[0].firstShotAt).isEqualTo("2026-09-14T09:00:00Z")
            assertThat(sessions[0].lastShotAt).isEqualTo("2026-09-14T09:30:00Z")
        }

    @Test
    fun aSessionWithoutShotsIsNotListed() =
        runTest {
            dao.upsert(session("with-shot"), fullShot())

            assertThat(dao.observeSessions().first().map { it.id }).containsExactly("with-shot")
            assertThat(dao.observeShots("never-used").first()).isEmpty()
        }

    // Expo: "records every swing of a swing-speed session"
    @Test
    fun recordsEverySwingOfASwingSpeedSession() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = null, timestamp = "2026-09-14T10:00:00Z"))
            dao.upsert(session("session-1"), fullShot(shotNumber = null, timestamp = "2026-09-14T10:01:00Z"))

            assertThat(dao.observeShots("session-1").first()).hasSize(2)
        }

    // Expo: "stores an absent shot number as null, the same as an explicit one"
    @Test
    fun storesAnAbsentShotNumberAsNull() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = null))

            assertThat(
                dao
                    .observeShots("session-1")
                    .first()
                    .single()
                    .shotNumber,
            ).isNull()
        }

    // Expo: "files the enriched version over the provisional one, not beside it"
    @Test
    fun filesTheEnrichedVersionOverTheProvisionalOne() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 7).copy(spinRpm = null, launchAngleVertical = null))
            dao.upsert(
                session("session-1"),
                fullShot(shotNumber = 7).copy(spinRpm = 2680.0, launchAngleVertical = 12.4),
            )

            val stored = dao.observeShots("session-1").first()
            assertThat(stored).hasSize(1)
            assertThat(stored[0].spinRpm).isEqualTo(2680.0)
            assertThat(stored[0].launchAngleVertical).isEqualTo(12.4)
        }

    // Expo: "counts that shot once in the session summary"
    @Test
    fun countsThatShotOnceInTheSessionSummary() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 7))
            dao.upsert(session("session-1"), fullShot(shotNumber = 7))

            assertThat(
                dao
                    .observeSessions()
                    .first()
                    .single()
                    .shotCount,
            ).isEqualTo(1)
        }

    // Expo: "keeps the same shot number in two different visits apart"
    @Test
    fun keepsTheSameShotNumberInTwoDifferentVisitsApart() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 1, club = "driver"))
            dao.upsert(session("session-2"), fullShot(shotNumber = 1, club = "7-iron"))

            assertThat(dao.observeShots("session-1").first()).hasSize(1)
            assertThat(dao.observeShots("session-2").first()).hasSize(1)
        }

    // Expo: "appends a shot the server could not number instead of merging it"
    @Test
    fun appendsAShotTheServerCouldNotNumberInsteadOfMergingIt() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = null, club = "driver"))
            dao.upsert(session("session-1"), fullShot(shotNumber = null, club = "7-iron"))

            assertThat(dao.observeShots("session-1").first()).hasSize(2)
        }

    @Test
    fun pairsTheSseEventWithTheSocketIoDetailOfTheSameShot() =
        runTest {
            // Over Wi-Fi each shot arrives twice: the SSE event (event id, no number or profile)
            // and the Socket.IO `shot` (number, profile). They share only the timestamp.
            val sse = eventShot(eventId = EVENT_ID, timestamp = "2026-09-14T10:00:00.123456")
            val socketIo =
                fullShot(
                    shotNumber = 4,
                    timestamp = "2026-09-14T10:00:00.123456",
                    profileId = "p1",
                    profileName = "Alex",
                )

            dao.upsert(session("session-1"), sse)
            dao.upsert(session("session-1"), socketIo)

            val stored = dao.observeShots("session-1").first().single()
            assertThat(stored.eventId).isEqualTo(EVENT_ID)
            assertThat(stored.shotNumber).isEqualTo(4)
            assertThat(stored.profileId).isEqualTo("p1")
            assertThat(stored.hasDetail).isTrue()
            assertThat(stored.rawJson).isEqualTo(socketIo.rawJson)
        }

    @Test
    fun pairsTheDetailFirstThenTheEventAndKeepsTheDetailsPayload() =
        runTest {
            val socketIo = fullShot(shotNumber = 4, timestamp = "2026-09-14T10:00:00.5")
            dao.upsert(session("session-1"), socketIo)
            dao.upsert(session("session-1"), eventShot(eventId = EVENT_ID, timestamp = "2026-09-14T10:00:00.5"))

            val stored = dao.observeShots("session-1").first().single()
            assertThat(stored.eventId).isEqualTo(EVENT_ID)
            assertThat(stored.shotNumber).isEqualTo(4)
            assertThat(stored.rawJson).isEqualTo(socketIo.rawJson)
        }

    @Test
    fun updatesAShotByItsEventIdLikeABleV2ProvisionalThenFinal() =
        runTest {
            dao.upsert(session("session-1"), eventShot(eventId = EVENT_ID).copy(spinRpm = null))
            dao.upsert(session("session-1"), eventShot(eventId = EVENT_ID).copy(spinRpm = 2500.0))

            assertThat(dao.observeShots("session-1").first().map { it.spinRpm }).containsExactly(2500.0)
        }

    // Expo feat/delete-shot: "leaves history without it and keeps the rest of the visit"
    @Test
    fun deletingLeavesHistoryWithoutItAndKeepsTheRestOfTheVisit() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 1, timestamp = "2026-09-14T10:00:00"))
            dao.upsert(session("session-1"), fullShot(shotNumber = 2, timestamp = "2026-09-14T10:05:00"))

            dao.deleteByTimestamps(listOf("2026-09-14T10:00:00"))

            assertThat(
                dao.observeShots("session-1").first().map { it.timestamp },
            ).containsExactly("2026-09-14T10:05:00")
            assertThat(
                dao
                    .observeSessions()
                    .first()
                    .single()
                    .shotCount,
            ).isEqualTo(1)
        }

    // Expo feat/delete-shot: "removes every visit's copy of that shot"
    @Test
    fun deletingRemovesEveryVisitsCopyOfThatShot() =
        runTest {
            dao.upsert(session("session-1"), fullShot(shotNumber = 4, timestamp = "2026-09-14T10:00:00"))
            dao.upsert(session("session-2"), fullShot(shotNumber = 4, timestamp = "2026-09-14T10:00:00"))
            dao.upsert(session("session-2"), fullShot(shotNumber = 5, timestamp = "2026-09-14T10:09:00"))

            dao.deleteByTimestamps(listOf("2026-09-14T10:00:00"))

            assertThat(dao.observeShots("session-1").first()).isEmpty()
            assertThat(dao.observeShots("session-2").first()).hasSize(1)
        }

    // Expo feat/delete-shot: "drops a visit from the list once its only shot is gone"
    @Test
    fun deletingDropsAVisitFromTheListOnceItsOnlyShotIsGone() =
        runTest {
            dao.upsert(session("older"), fullShot(shotNumber = 1, timestamp = "2026-09-13T09:00:00"))
            dao.upsert(session("newer"), fullShot(shotNumber = 1, timestamp = "2026-09-14T09:00:00"))

            dao.deleteByTimestamps(listOf("2026-09-14T09:00:00"))

            assertThat(dao.observeSessions().first().map { it.id }).containsExactly("older")
        }

    // Expo feat/delete-shot: "leaves history untouched when no stored shot has that timestamp"
    @Test
    fun deletingLeavesHistoryUntouchedWhenNoStoredShotHasThatTimestamp() =
        runTest {
            dao.upsert(session("session-1"), fullShot(timestamp = "2026-09-14T10:00:00"))

            dao.deleteByTimestamps(listOf("2026-09-14T10:00:00.000001"))

            assertThat(dao.observeShots("session-1").first()).hasSize(1)
        }

    @Test
    fun clearAllEmptiesEverySessionAndShot() =
        runTest {
            dao.upsert(session("older"), fullShot(shotNumber = 1, timestamp = "2026-09-13T09:00:00"))
            dao.upsert(session("newer"), fullShot(shotNumber = 1, timestamp = "2026-09-14T09:00:00"))

            dao.clearAll()

            assertThat(dao.observeSessions().first()).isEmpty()
            assertThat(dao.shotCount()).isEqualTo(0)
        }

    @Test
    fun observersSeeEveryWrite() =
        runTest {
            dao.observeSessions().test {
                assertThat(awaitItem()).isEmpty()

                dao.upsert(session("session-1"), fullShot(shotNumber = 1, timestamp = "2026-09-14T10:00:00"))
                assertThat(awaitItem().single().shotCount).isEqualTo(1)

                dao.upsert(session("session-1"), fullShot(shotNumber = 2, timestamp = "2026-09-14T10:05:00"))
                assertThat(awaitItem().single().lastShotAt).isEqualTo("2026-09-14T10:05:00")

                dao.clearAll()
                assertThat(awaitItem()).isEmpty()
            }
        }

    private companion object {
        const val EVENT_ID = "3f2b6a8e-1c4d-4e5f-8a9b-0c1d2e3f4a5b"

        fun session(id: String) =
            SessionEntity(id = id, startedAtEpochMillis = 1_000L, host = "pi.local:8080", transport = "WIFI")

        /** Expo's `makeShot()`: a Socket.IO row with every displayed measurement set. */
        fun fullShot(
            shotNumber: Int? = 1,
            timestamp: String = "2026-09-14T10:00:00Z",
            club: String = "driver",
            profileId: String? = null,
            profileName: String? = null,
        ) = ShotEntity(
            sessionId = "",
            shotNumber = shotNumber,
            timestamp = timestamp,
            club = club,
            profileId = profileId,
            profileName = profileName,
            mode = "rolling-buffer",
            ballSpeedMph = 148.2,
            clubSpeedMph = 104.1,
            smashFactor = 1.42,
            estimatedCarryYards = 266.0,
            carrySpinAdjusted = 271.0,
            carryRangeLow = 258.0,
            carryRangeHigh = 274.0,
            launchAngleVertical = 12.4,
            launchAngleHorizontal = -1.2,
            launchAngleConfidence = 0.8,
            angleSource = "radar",
            clubAngleDeg = -3.1,
            clubPathDeg = 1.4,
            spinAxisDeg = -5.2,
            spinRpm = 2680.0,
            spinSource = "measured",
            spinQuality = "medium",
            hasDetail = true,
            rawJson = """{"shot_number":$shotNumber,"timestamp":"$timestamp"}""",
        )

        /** An SSE/BLE shot event: an event id, the core metrics, no number or profile. */
        fun eventShot(
            eventId: String,
            timestamp: String = "2026-09-14T10:00:00Z",
        ) = ShotEntity(
            sessionId = "",
            timestamp = timestamp,
            eventId = eventId,
            club = "driver",
            ballSpeedMph = 148.0,
            estimatedCarryYards = 265.0,
            spinRpm = 2600.0,
            rawJson = """{"event_id":"$eventId","timestamp":"$timestamp"}""",
        )
    }
}
