// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [DefaultShotHistoryRepository]'s schema v2 surface (plan F3) over a real in-memory database:
 * imported sessions, the user's star and note, and one club's shots across sessions.
 */
class ShotHistoryV2RepositoryTest {
    private class Harness(
        scope: TestScope,
        openDatabase: () -> ShotHistoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase,
    ) {
        private var sessionCount = 0
        var clock = 0L
        val repository =
            DefaultShotHistoryRepository(
                openDatabase = openDatabase,
                scope = scope.backgroundScope,
                fallbackDatabase = fallbackDatabase,
                now = { clock },
                newSessionId = { "session-${++sessionCount}" },
                log = {},
            )

        /** Starts a live session at [startedAt] and files [shots] under it. */
        suspend fun liveSession(
            startedAt: Long,
            vararg shots: ShotDetail,
        ): String {
            clock = startedAt
            repository.startSession("pi.local:8080", TransportType.WIFI)
            shots.forEach { repository.record(PiLiveShot(it, rawJson = null)) }
            repository.awaitWrites()
            return checkNotNull(repository.currentSessionId.value)
        }
    }

    private fun runV2Test(
        openDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        fallbackDatabase: () -> ShotHistoryDatabase = ::inMemoryDatabase,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        body(Harness(this, openDatabase, fallbackDatabase))
    }

    @Test
    fun anImportedSessionIsLeftOutOfLiveHistoryAndListedWhenAskedFor() =
        runV2Test { h ->
            val mine = h.liveSession(2_000L, detail(1, T1))
            val theirs = h.repository.importSession(imported(detail(1, T2)))

            assertThat(
                h.repository
                    .sessions()
                    .first()
                    .map { it.id },
            ).containsExactly(mine)
            val all = h.repository.sessions(includeImported = true).first()
            assertThat(all.map { it.id }).containsExactly(theirs, mine)
            val importedSession = all.first()
            assertThat(importedSession.source).isEqualTo(SessionSource.IMPORTED)
            assertThat(importedSession.ownerName).isEqualTo("Sam")
            assertThat(importedSession.title).isEqualTo("Sam's range day")
            assertThat(importedSession.includeInStats).isFalse()
            assertThat(importedSession.startedAtEpochMillis).isEqualTo(500L)
            assertThat(all.last().source).isEqualTo(SessionSource.LOCAL)
        }

    @Test
    fun anImportIsNeverTheCurrentSessionAndLiveShotsAreNotFiledUnderIt() =
        runV2Test { h ->
            val mine = h.liveSession(1_000L)
            h.repository.importSession(imported(detail(1, T1)))

            h.repository.record(PiLiveShot(detail(2, T2), rawJson = null))
            h.repository.awaitWrites()

            assertThat(h.repository.currentSessionId.value).isEqualTo(mine)
            assertThat(
                h.repository
                    .shots(mine)
                    .first()
                    .map { it.detail.timestamp },
            ).containsExactly(T2)
        }

    @Test
    fun anImportDropsTheSharersProfileAndARepeatedShotNumber() =
        runV2Test { h ->
            val id =
                h.repository.importSession(
                    imported(
                        detail(1, T1, profileId = "p1", profileName = "Sam"),
                        detail(1, T2, profileId = "p1", profileName = "Sam"),
                    ),
                )

            val shots = h.repository.shots(checkNotNull(id)).first()
            assertThat(shots.map { it.detail.timestamp }).containsExactly(T2, T1)
            assertThat(shots.map { it.detail.shotNumber }).containsExactly(null, 1)
            assertThat(shots.map { it.detail.profileId }).containsExactly(null, null)
            assertThat(shots.map { it.detail.profileName }).containsExactly(null, null)
            assertThat(shots.first().detail.ballSpeedMph).isEqualTo(150.0)
        }

    @Test
    fun clearAllKeepsImportsAndClearImportedRemovesOnlyThem() =
        runV2Test { h ->
            h.liveSession(1_000L, detail(1, T1))
            val theirs = h.repository.importSession(imported(detail(1, T1)))

            h.repository.clearAll()
            h.repository.awaitWrites()
            assertThat(
                h.repository
                    .sessions(includeImported = true)
                    .first()
                    .map { it.id },
            ).containsExactly(theirs)

            val mine = h.liveSession(3_000L, detail(1, T3))
            h.repository.clearImported()
            h.repository.awaitWrites()
            assertThat(
                h.repository
                    .sessions(includeImported = true)
                    .first()
                    .map { it.id },
            ).containsExactly(mine)
        }

    @Test
    fun aStarAndANoteRoundTrip() =
        runV2Test { h ->
            val mine = h.liveSession(1_000L, detail(1, T1))
            h.repository.shots(mine).test {
                val shot = awaitItem().single()
                assertThat(shot.starred).isFalse()
                assertThat(shot.note).isNull()

                h.repository.setStarred(shot.id, true)
                assertThat(awaitItem().single().starred).isTrue()

                h.repository.setNote(shot.id, "flushed")
                assertThat(awaitItem().single().note).isEqualTo("flushed")

                h.repository.setStarred(shot.id, false)
                val unstarred = awaitItem().single()
                assertThat(unstarred.starred).isFalse()
                assertThat(unstarred.note).isEqualTo("flushed")
            }
        }

    @Test
    fun anImportCountsInClubStatsOnlyOnceOptedIn() =
        runV2Test { h ->
            val mine = h.liveSession(1_000L, detail(1, T1, club = "7-iron"), detail(2, T2, club = "driver"))
            val theirs = checkNotNull(h.repository.importSession(imported(detail(1, T3, club = "7-iron"))))

            h.repository.shotsForClub(GolfClub.IRON_7).test {
                assertThat(awaitItem().map { it.sessionId }).containsExactly(mine)

                h.repository.setIncludeInStats(theirs, true)
                // Newest session first: the import started at 500, before mine.
                assertThat(awaitItem().map { it.sessionId }).containsExactly(mine, theirs)
            }
        }

    @Test
    fun clubShotsCanBeLimitedToRecentSessionsOrASinceTimeOrAProfile() =
        runV2Test { h ->
            val first = h.liveSession(1_000L, detail(1, T1, club = "pw", profileId = "ann"))
            val second = h.liveSession(2_000L, detail(1, T2, club = "pw", profileId = "bo"))
            val third = h.liveSession(3_000L, detail(1, T3, club = "pw", profileId = "ann"))

            assertThat(
                h.repository
                    .shotsForClub(GolfClub.PITCHING_WEDGE)
                    .first()
                    .map { it.sessionId },
            ).containsExactly(third, second, first)
            assertThat(
                h.repository
                    .shotsForClub(GolfClub.PITCHING_WEDGE, ShotWindow.LastSessions(2))
                    .first()
                    .map { it.sessionId },
            ).containsExactly(third, second)
            assertThat(
                h.repository
                    .shotsForClub(GolfClub.PITCHING_WEDGE, ShotWindow.Since(2_000L))
                    .first()
                    .map { it.sessionId },
            ).containsExactly(third, second)
            assertThat(
                h.repository
                    .shotsForClub(GolfClub.PITCHING_WEDGE, profileId = "ann")
                    .first()
                    .map { it.sessionId },
            ).containsExactly(third, first)
        }

    @Test
    fun aSessionNoteRoundTrips() =
        runV2Test { h ->
            val mine = h.liveSession(1_000L, detail(1, T1))

            h.repository.setSessionNote(mine, "windy")
            h.repository.awaitWrites()

            assertThat(
                h.repository
                    .sessions()
                    .first()
                    .single()
                    .note,
            ).isEqualTo("windy")
        }

    @Test
    fun withNoDatabaseAnImportStoresNothingAndSaysSo() =
        runV2Test(openDatabase = { error("disk I/O error") }, fallbackDatabase = { error("no memory") }) { h ->
            assertThat(h.repository.importSession(imported(detail(1, T1)))).isNull()
        }

    @Test
    fun anImportIntoTheFallbackDatabaseIsStored() =
        runV2Test(openDatabase = { error("disk I/O error") }) { h ->
            assertThat(h.repository.importSession(imported(detail(1, T1)))).isNotNull()
            assertThat(h.repository.isPersistent.value).isFalse()
        }

    private companion object {
        const val T1 = "2026-09-14T10:00:00"
        const val T2 = "2026-09-14T10:05:00"
        const val T3 = "2026-09-14T10:10:00"

        fun inMemoryDatabase(): ShotHistoryDatabase = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()

        fun detail(
            number: Int?,
            timestamp: String,
            club: String = "driver",
            profileId: String? = null,
            profileName: String? = null,
        ) = ShotDetail(
            timestamp = timestamp,
            shotNumber = number,
            ballSpeedMph = 150.0,
            club = club,
            profileId = profileId,
            profileName = profileName,
        )

        fun imported(vararg shots: ShotDetail) =
            ImportedSession(
                ownerName = "Sam",
                title = "Sam's range day",
                startedAtEpochMillis = 500L,
                shots = shots.toList(),
            )
    }
}
