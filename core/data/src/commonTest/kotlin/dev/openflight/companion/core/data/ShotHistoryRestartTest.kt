// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.database.ShotHistoryDatabase
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * #148: the current session survives an app restart (a second repository over the same database
 * and settings DataStore), so a BLE `session_cleared` after the restart still reaches its shots.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShotHistoryRestartTest {
    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "openflight-restart-${Random.nextLong().toULong()}"
    private val job = Job()
    private val dataStore =
        createSettingsDataStore(
            path = (directory / DataStoreSettingsRepository.FILE_NAME).toString(),
            scope = CoroutineScope(job),
        )
    private val database: ShotHistoryDatabase = inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase()
    private var sessionCount = 0
    private var nowMillis = START

    @AfterTest
    fun tearDown() {
        job.cancel()
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    /** One app run. */
    private fun TestScope.launchApp() =
        DefaultShotHistoryRepository(
            openDatabase = { database },
            scope = backgroundScope,
            now = { nowMillis },
            newSessionId = { "session-${++sessionCount}" },
            log = {},
            savedSession = dataStore,
        )

    @Test
    fun aRestartWithinTheIdleGapContinuesTheSessionAndASessionClearedRemovesItsEarlierShots() =
        runTest(UnconfinedTestDispatcher()) {
            val before = launchApp()
            before.startSession(null, TransportType.BLUETOOTH)
            before.record(shot(1, "2026-10-27T09:00:00", ALEX))
            before.record(shot(2, "2026-10-27T09:01:00", SAM))
            before.awaitWrites()

            nowMillis = START + IDLE_GAP
            val after = launchApp()
            after.startSession(null, TransportType.BLUETOOTH)
            after.record(shot(3, "2026-10-27T09:31:00", ALEX))
            after.awaitWrites()
            assertThat(after.currentSessionId.value).isEqualTo("session-1")

            after.clearProfile(ALEX)
            after.awaitWrites()

            assertThat(after.sessions().first().map { it.id }).containsExactly("session-1")
            assertThat(after.shots("session-1").first().map { it.detail.timestamp })
                .containsExactly("2026-10-27T09:01:00")
        }

    @Test
    fun aRestartAfterTheIdleGapStartsANewSession() =
        runTest(UnconfinedTestDispatcher()) {
            val before = launchApp()
            before.startSession(null, TransportType.BLUETOOTH)
            before.record(shot(1, "2026-10-27T09:00:00", ALEX))
            before.awaitWrites()

            nowMillis = START + IDLE_GAP + 1
            val after = launchApp()
            after.startSession(null, TransportType.BLUETOOTH)
            after.record(shot(1, "2026-10-27T10:00:00", ALEX))
            after.clearProfile(ALEX)
            after.awaitWrites()

            assertThat(after.currentSessionId.value).isEqualTo("session-2")
            assertThat(after.shots("session-1").first().map { it.detail.timestamp })
                .containsExactly("2026-10-27T09:00:00")
            assertThat(after.shots("session-2").first()).isEmpty()
        }

    @Test
    fun aRestartThatConnectsOverAnotherTransportStartsANewSession() =
        runTest(UnconfinedTestDispatcher()) {
            val before = launchApp()
            before.startSession(null, TransportType.BLUETOOTH)
            before.record(shot(1, "2026-10-27T09:00:00", ALEX))
            before.awaitWrites()

            val after = launchApp()
            after.startSession("pi.local:8080", TransportType.WIFI)
            after.awaitWrites()

            assertThat(after.currentSessionId.value).isEqualTo("session-2")
        }

    private companion object {
        const val IDLE_GAP = DefaultShotHistoryRepository.SESSION_IDLE_GAP_MILLIS
        const val START = 1_000_000L
        const val ALEX = "alex"
        const val SAM = "sam"

        fun shot(
            number: Int,
            timestamp: String,
            profileId: String,
        ) = PiLiveShot(
            ShotDetail(timestamp = timestamp, shotNumber = number, club = "driver", profileId = profileId),
            rawJson = null,
        )
    }
}
