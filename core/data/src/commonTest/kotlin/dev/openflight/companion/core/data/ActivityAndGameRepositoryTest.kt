// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.CalloutContext
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** [DefaultActivityRepository] over a real in-memory database, and [DefaultActiveGameRepository] (plan F3). */
class ActivityAndGameRepositoryTest {
    @Test
    fun activitiesAreRecordedListedNewestFirstFilteredAndDeleted() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = DefaultActivityRepository(database(), log = {})

            repository.activities().test {
                assertThat(awaitItem()).isEmpty()

                repository.record(activity("a1", "TARGET_CALLOUT", startedAt = 1_000L))
                assertThat(awaitItem().map { it.id }).containsExactly("a1")

                repository.record(activity("a2", "GOLF_PONG", startedAt = 2_000L))
                assertThat(awaitItem().map { it.id }).containsExactly("a2", "a1")

                repository.delete("a2")
                assertThat(awaitItem().map { it.id }).containsExactly("a1")
            }
            assertThat(repository.activities("GOLF_PONG").first()).isEmpty()
            assertThat(
                repository.activities("TARGET_CALLOUT").first().single(),
            ).isEqualTo(activity("a1", "TARGET_CALLOUT", 1_000L))
        }

    @Test
    fun recordingTheSameIdReplacesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = DefaultActivityRepository(database(), log = {})

            repository.record(activity("a1", "TARGET_CALLOUT", 1_000L))
            repository.record(activity("a1", "TARGET_CALLOUT", 1_000L).copy(headline = "You won!"))

            assertThat(repository.activities().first().map { it.headline }).containsExactly("You won!")
        }

    @Test
    fun anActivityKeepsItsSessionLinkUntilThatSessionIsCleared() =
        runTest(UnconfinedTestDispatcher()) {
            val database = database()
            val history = DefaultShotHistoryRepository(database, backgroundScope, newSessionId = { "s1" }, log = {})
            val activities = DefaultActivityRepository(database, log = {})
            history.startSession("pi.local:8080", TransportType.WIFI)
            history.record(PiLiveShot(ShotDetail(timestamp = "2026-09-14T10:00:00", shotNumber = 1), rawJson = null))
            history.awaitWrites()

            activities.record(activity("a1", "TARGET_CALLOUT", 1_000L, sessionId = "s1"))
            assertThat(
                activities
                    .activities()
                    .first()
                    .single()
                    .sessionId,
            ).isEqualTo("s1")

            history.clearAll()
            history.awaitWrites()
            assertThat(
                activities
                    .activities()
                    .first()
                    .single()
                    .sessionId,
            ).isNull()
        }

    @Test
    fun theActiveGameIsSetAndCleared() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = DefaultActiveGameRepository()
            val context = CalloutContext(gameTitle = "Target call-out", targetYards = 80.0, playerName = "Ann")

            repository.activeGame.test {
                assertThat(awaitItem()).isNull()
                repository.set(context)
                assertThat(awaitItem()).isEqualTo(context)
                repository.clear()
                assertThat(awaitItem()).isNull()
            }
        }

    private fun kotlinx.coroutines.test.TestScope.database() =
        HistoryDatabase(
            openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
            scope = backgroundScope,
            log = {},
        )

    private companion object {
        fun activity(
            id: String,
            type: String,
            startedAt: Long,
            sessionId: String? = null,
        ) = Activity(
            id = id,
            type = type,
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = startedAt + 60_000L,
            title = "Target 80 yd",
            headline = "4.2 yd",
            playersJson = """[{"name":"Ann"}]""",
            resultJson = "{}",
            sessionId = sessionId,
        )
    }
}
