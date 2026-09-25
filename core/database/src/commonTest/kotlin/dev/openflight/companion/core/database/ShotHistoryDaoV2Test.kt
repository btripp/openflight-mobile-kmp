// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.database

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Schema v2's history queries (plan F3): imported sessions, the LOCAL-only deletes (A8), the
 * user's star and note, and one club's shots across sessions.
 */
class ShotHistoryDaoV2Test {
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

    @Test
    fun anImportedSessionWithTheSameTimestampsSurvivesAPiDelete() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            dao.upsert(local("mine"), shot(2, T2))
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1), shot(2, T2)))

            dao.deleteByTimestamps(listOf(T1))

            assertThat(dao.observeShots("mine").first().map { it.timestamp }).containsExactly(T2)
            assertThat(dao.observeShots("theirs").first().map { it.timestamp }).containsExactly(T2, T1)
        }

    @Test
    fun anImportedSessionSurvivesASessionClearedMirrorThatEmptiesTheLocalOne() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1)))

            // `session_cleared` is mirrored as a delete of the cleared rows' timestamps.
            dao.deleteByTimestamps(listOf(T1))

            assertThat(dao.observeSessions(includeImported = true).first().map { it.id }).containsExactly("theirs")
        }

    @Test
    fun clearAllDeletesOnlyThePhonesOwnSessions() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1)))

            dao.clearAll()

            assertThat(dao.observeSessions(includeImported = true).first().map { it.id }).containsExactly("theirs")
            assertThat(dao.shotCount()).isEqualTo(1)
        }

    @Test
    fun clearImportedDeletesOnlyImportedSessions() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1)))

            dao.clearImported()

            assertThat(dao.observeSessions(includeImported = true).first().map { it.id }).containsExactly("mine")
        }

    @Test
    fun liveHistoryListsOnlyThePhonesOwnSessions() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T2)))

            assertThat(dao.observeSessions().first().map { it.id }).containsExactly("mine")
            val all = dao.observeSessions(includeImported = true).first()
            assertThat(all.map { it.id }).containsExactly("theirs", "mine")
            assertThat(all[0].source).isEqualTo(SessionEntity.SOURCE_IMPORTED)
            assertThat(all[0].ownerName).isEqualTo("Sam")
            assertThat(all[0].includeInStats).isFalse()
            assertThat(all[1].source).isEqualTo(SessionEntity.SOURCE_LOCAL)
            assertThat(all[1].includeInStats).isTrue()
        }

    @Test
    fun anImportedSessionStoresEveryShotInItsOwnRow() =
        runTest {
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1), shot(2, T2), shot(null, T3)))

            assertThat(dao.observeShots("theirs").first().map { it.timestamp }).containsExactly(T3, T2, T1)
        }

    @Test
    fun aStarAndANoteRoundTripAndSurviveALaterCopyOfTheShot() =
        runTest {
            val id = dao.upsert(local("mine"), shot(7, T1))

            dao.setStarred(id, true)
            dao.setNote(id, "pured it")
            // The shot_update of the same shot merges into the row.
            dao.upsert(local("mine"), shot(7, T1).copy(spinRpm = 2_900.0))

            val stored = dao.observeShots("mine").first().single()
            assertThat(stored.starred).isTrue()
            assertThat(stored.note).isEqualTo("pured it")
            assertThat(stored.spinRpm).isEqualTo(2_900.0)

            dao.setStarred(id, false)
            dao.setNote(id, null)
            val cleared = dao.observeShots("mine").first().single()
            assertThat(cleared.starred).isFalse()
            assertThat(cleared.note).isNull()
        }

    @Test
    fun aSessionsStatsFlagAndNoteCanBeChanged() =
        runTest {
            dao.insertSessionWithShots(imported("theirs"), listOf(shot(1, T1)))

            dao.setIncludeInStats("theirs", true)
            dao.setSessionNote("theirs", "range day")

            val session = dao.observeSessions(includeImported = true).first().single()
            assertThat(session.includeInStats).isTrue()
            assertThat(session.note).isEqualTo("range day")
        }

    @Test
    fun shotsForAClubSpanSessionsNewestFirstAndSkipOtherClubs() =
        runTest {
            dao.upsert(local("old", startedAt = 1_000L), shot(1, T1, club = "7-iron"))
            dao.upsert(local("old", startedAt = 1_000L), shot(2, T2, club = "driver"))
            dao.upsert(local("new", startedAt = 2_000L), shot(1, T3, club = "7-iron"))

            val rows =
                dao
                    .observeShotsForClub(
                        "7-iron",
                        profileId = null,
                        sinceEpochMillis = Long.MIN_VALUE,
                        sessionLimit = -1,
                    ).first()

            assertThat(rows.map { it.sessionId to it.timestamp }).containsExactly("new" to T3, "old" to T1)
        }

    @Test
    fun shotsForAClubLeaveOutSessionsNotInStatsUntilOptedIn() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1, club = "7-iron"))
            dao.insertSessionWithShots(imported("theirs", startedAt = 5_000L), listOf(shot(1, T2, club = "7-iron")))

            val before = dao.observeShotsForClub("7-iron", null, Long.MIN_VALUE, -1).first()
            assertThat(before.map { it.sessionId }).containsExactly("mine")

            dao.setIncludeInStats("theirs", true)
            val after = dao.observeShotsForClub("7-iron", null, Long.MIN_VALUE, -1).first()
            assertThat(after.map { it.sessionId }).containsExactly("theirs", "mine")
        }

    @Test
    fun shotsForAClubCanBeLimitedToRecentSessionsASinceTimeOrAProfile() =
        runTest {
            dao.upsert(local("s1", startedAt = 1_000L), shot(1, T1, club = "pw", profileId = "ann"))
            dao.upsert(local("s2", startedAt = 2_000L), shot(1, T2, club = "pw", profileId = "bo"))
            dao.upsert(local("s3", startedAt = 3_000L), shot(1, T3, club = "pw", profileId = "ann"))
            // A newer session without a PW doesn't use up the session limit.
            dao.upsert(local("s4", startedAt = 4_000L), shot(1, T4, club = "driver", profileId = "ann"))

            val lastTwo = dao.observeShotsForClub("pw", null, Long.MIN_VALUE, sessionLimit = 2).first()
            assertThat(lastTwo.map { it.sessionId }).containsExactly("s3", "s2")

            val since = dao.observeShotsForClub("pw", null, sinceEpochMillis = 2_000L, sessionLimit = -1).first()
            assertThat(since.map { it.sessionId }).containsExactly("s3", "s2")

            val ann = dao.observeShotsForClub("pw", profileId = "ann", Long.MIN_VALUE, sessionLimit = 2).first()
            assertThat(ann.map { it.sessionId }).containsExactly("s3", "s1")
        }

    @Test
    fun anActivityOutlivesItsSessionAndLosesOnlyTheLink() =
        runTest {
            dao.upsert(local("mine"), shot(1, T1))
            database.activityDao().upsert(activity("a1", sessionId = "mine"))

            dao.clearAll()

            val stored =
                database
                    .activityDao()
                    .observeActivities()
                    .first()
                    .single()
            assertThat(stored.id).isEqualTo("a1")
            assertThat(stored.sessionId).isNull()
        }

    @Test
    fun anActivityForASessionNotYetStoredIsKeptWithoutTheLink() =
        runTest {
            database.activityDao().upsert(activity("a1", sessionId = "never-stored"))

            assertThat(
                database
                    .activityDao()
                    .observeActivities()
                    .first()
                    .single()
                    .sessionId,
            ).isNull()
        }

    @Test
    fun activitiesAreListedNewestFirstFilteredByTypeAndDeletedById() =
        runTest {
            val activities = database.activityDao()
            activities.upsert(activity("a1", type = "TARGET", startedAt = 1_000L))
            activities.upsert(activity("a2", type = "PONG", startedAt = 2_000L))
            activities.upsert(activity("a3", type = "TARGET", startedAt = 3_000L))
            // Recording the same id again replaces it.
            activities.upsert(activity("a1", type = "TARGET", startedAt = 1_000L).copy(headline = "3.1 yd"))

            assertThat(activities.observeActivities().first().map { it.id }).containsExactly("a3", "a2", "a1")
            assertThat(activities.observeActivities("TARGET").first().map { it.headline })
                .containsExactly("4.2 yd", "3.1 yd")

            activities.delete("a3")
            assertThat(activities.observeActivities("TARGET").first().map { it.id }).containsExactly("a1")
            activities.delete("a1")
            activities.delete("a2")
            assertThat(activities.observeActivities().first()).isEmpty()
        }

    private companion object {
        const val T1 = "2026-09-14T10:00:00"
        const val T2 = "2026-09-14T10:05:00"
        const val T3 = "2026-09-14T10:10:00"
        const val T4 = "2026-09-14T10:15:00"

        fun local(
            id: String,
            startedAt: Long = 1_000L,
        ) = SessionEntity(id = id, startedAtEpochMillis = startedAt, host = "pi.local:8080", transport = "WIFI")

        fun imported(
            id: String,
            startedAt: Long = 500L,
        ) = SessionEntity(
            id = id,
            startedAtEpochMillis = startedAt,
            host = null,
            transport = "UNKNOWN",
            source = SessionEntity.SOURCE_IMPORTED,
            ownerName = "Sam",
            includeInStats = false,
        )

        fun shot(
            number: Int?,
            timestamp: String,
            club: String = "driver",
            profileId: String? = null,
        ) = ShotEntity(
            sessionId = "",
            shotNumber = number,
            timestamp = timestamp,
            club = club,
            profileId = profileId,
            ballSpeedMph = 148.2,
            spinRpm = 2_680.0,
            hasDetail = true,
            rawJson = "{}",
        )

        fun activity(
            id: String,
            type: String = "TARGET",
            startedAt: Long = 1_000L,
            sessionId: String? = null,
        ) = ActivityEntity(
            id = id,
            type = type,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = startedAt + 60_000L,
            title = "Target 80 yd",
            headline = "4.2 yd",
            playersJson = "[]",
            resultJson = "{}",
            sessionId = sessionId,
        )
    }
}
