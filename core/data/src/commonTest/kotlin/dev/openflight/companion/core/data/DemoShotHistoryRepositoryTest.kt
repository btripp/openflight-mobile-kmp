// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
 * Plan F14: Demo mode's history shares the player's database but never mixes with it: demo
 * sessions are tagged `DEMO`, listed and counted only in the demo world, and "Clear demo data"
 * leaves the player's sessions alone (and the other way round).
 */
class DemoShotHistoryRepositoryTest {
    private class Harness(
        scope: TestScope,
    ) {
        private var sessionCount = 0
        private val database =
            HistoryDatabase(
                openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
                scope = scope.backgroundScope,
                log = {},
            )
        val real =
            DefaultShotHistoryRepository(
                database = database,
                scope = scope.backgroundScope,
                now = { 1_000L },
                newSessionId = { "real-${++sessionCount}" },
                log = {},
            )
        private val demoInner =
            DefaultShotHistoryRepository(
                database = database,
                scope = scope.backgroundScope,
                now = { 2_000L },
                newSessionId = { "demo-${++sessionCount}" },
                log = {},
                demoWorld = true,
            )
        val demo: DemoShotHistoryRepository = DefaultDemoShotHistoryRepository(demoInner)

        suspend fun settle() {
            real.awaitWrites()
            demoInner.awaitWrites()
        }
    }

    private fun runDemoTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest(UnconfinedTestDispatcher()) { body(Harness(this)) }

    @Test
    fun liveDemoShotsAreFiledAsDemoSessionsAndOnlyListedInTheDemoWorld() =
        runDemoTest { h ->
            h.real.startSession("pi.local:8080", TransportType.WIFI)
            h.real.record(PiLiveShot(detail(1, T1, "7-iron"), rawJson = null))
            h.demo.startSession(null, TransportType.WIFI)
            h.demo.record(PiLiveShot(detail(1, T2, "7-iron"), rawJson = null))
            h.settle()

            val real = h.real.sessions(includeImported = true).first()
            assertThat(real.map { it.source }).containsExactly(SessionSource.LOCAL)
            val demo = h.demo.sessions().first()
            assertThat(demo.map { it.source }).containsExactly(SessionSource.DEMO)
            assertThat(demo.single().id).isEqualTo(h.demo.currentSessionId.value)
        }

    @Test
    fun demoShotsNeverReachTheRealBagStatsAndTheOtherWayRound() =
        runDemoTest { h ->
            h.real.startSession("pi.local:8080", TransportType.WIFI)
            h.real.record(PiLiveShot(detail(1, T1, "7-iron"), rawJson = null))
            h.demo.seedIfEmpty(listOf(DemoSeedSession(500L, listOf(detail(1, T2, "7-iron")))))
            h.settle()

            assertThat(
                h.real
                    .shotsForClub(GolfClub.IRON_7)
                    .first()
                    .map { it.detail.timestamp },
            ).containsExactly(T1)
            assertThat(
                h.demo
                    .shotsForClub(GolfClub.IRON_7)
                    .first()
                    .map { it.detail.timestamp },
            ).containsExactly(T2)
        }

    @Test
    fun seedingHappensOnceAndKeepsTheDemoProfiles() =
        runDemoTest { h ->
            val sessions =
                listOf(
                    DemoSeedSession(500L, listOf(detail(2, T2, "driver", "demo-ann"), detail(1, T1, "pw", "demo-bob"))),
                    DemoSeedSession(400L, listOf(detail(1, T0, "7-iron", "demo-ann")), title = "Range day"),
                )

            assertThat(h.demo.seedIfEmpty(sessions)).isTrue()
            assertThat(h.demo.seedIfEmpty(sessions)).isFalse()

            val stored = h.demo.sessions().first()
            assertThat(stored.map { it.shotCount }).containsExactly(2, 1)
            val first = stored.first { it.shotCount == 2 }
            assertThat(
                h.demo
                    .shots(first.id, profileId = "demo-bob")
                    .first()
                    .map { it.detail.club },
            ).containsExactly("pw")
            assertThat(h.real.sessions(includeImported = true).first()).isEmpty()
        }

    @Test
    fun clearingDemoDataLeavesThePlayersSessionsAndClearingTheirsLeavesDemoData() =
        runDemoTest { h ->
            h.real.startSession("pi.local:8080", TransportType.WIFI)
            h.real.record(PiLiveShot(detail(1, T1), rawJson = null))
            h.demo.seedIfEmpty(listOf(DemoSeedSession(500L, listOf(detail(1, T1)))))
            h.settle()

            // A Pi delete of the same timestamp is about the player's shot only.
            h.real.deleteShot(T1)
            h.settle()
            assertThat(h.real.sessions().first()).isEmpty()
            assertThat(
                h.demo
                    .sessions()
                    .first()
                    .map { it.shotCount },
            ).containsExactly(1)

            h.real.startSession("pi.local:8080", TransportType.WIFI)
            h.real.record(PiLiveShot(detail(2, T2), rawJson = null))
            h.demo.clearAll()
            h.settle()
            assertThat(h.demo.sessions().first()).isEmpty()
            assertThat(
                h.real
                    .sessions()
                    .first()
                    .map { it.shotCount },
            ).containsExactly(1)

            // With the demo data gone, the next Demo mode seeds it again.
            assertThat(h.demo.seedIfEmpty(listOf(DemoSeedSession(500L, listOf(detail(1, T3)))))).isTrue()
            h.real.clearAll()
            h.settle()
            assertThat(
                h.demo
                    .sessions()
                    .first()
                    .map { it.shotCount },
            ).containsExactly(1)
        }

    private companion object {
        const val T0 = "2026-09-13T10:00:00"
        const val T1 = "2026-09-14T10:00:00"
        const val T2 = "2026-09-14T10:05:00"
        const val T3 = "2026-09-14T10:10:00"

        fun detail(
            number: Int?,
            timestamp: String,
            club: String = "driver",
            profileId: String? = null,
        ) = ShotDetail(
            timestamp = timestamp,
            shotNumber = number,
            ballSpeedMph = 150.0,
            club = club,
            profileId = profileId,
            profileName = profileId?.removePrefix("demo-"),
        )
    }
}
