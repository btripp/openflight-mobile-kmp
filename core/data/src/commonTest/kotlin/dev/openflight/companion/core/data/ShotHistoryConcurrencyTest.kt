// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #73: the in-memory history is changed from several threads at once (the transport's shot
 * collector and local deletes, all on [Dispatchers.Default]). No change may be lost: a deleted
 * shot must not come back and a new shot must not go missing.
 */
class ShotHistoryConcurrencyTest {
    @Test
    fun concurrentRecordsAndDeletesLoseNoUpdate() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                repeat(ROUNDS) { round -> raceOneRound(round) }
            }
        }

    /**
     * Seeds [SEEDED] shots, then deletes all of them from [SEEDED] parallel coroutines while the
     * transport delivers [ADDED] new ones. Afterwards exactly the new shots must be left.
     */
    private suspend fun raceOneRound(round: Int) {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val transport = FakeShotTransport("ble")
            val repository =
                DefaultShotRepository(
                    settings = FakeSettingsRepository(transport = TransportType.BLUETOOTH),
                    bluetoothTransport = transport,
                    wifiTransportFactory = { error("Wi-Fi is not used here") },
                    scope = scope,
                    piControl = fakePiControlClient(),
                )
            repository.start()
            transport.shots.subscriptionCount.first { it > 0 }

            val base = round * (SEEDED + ADDED + 1)
            val seeded = (base until base + SEEDED).map(::shotId)
            (base until base + SEEDED).forEach { transport.shots.emit(shot(it)) }
            withTimeout(WAIT) { repository.history.first { it.size == SEEDED } }

            val added = (base + SEEDED until base + SEEDED + ADDED).toList()
            val sentinel = base + SEEDED + ADDED
            val deletes = seeded.map { id -> scope.launch { repository.deleteShot(id) } }
            val adds =
                scope.launch {
                    added.forEach { transport.shots.emit(shot(it)) }
                    transport.shots.emit(shot(sentinel))
                }
            (deletes + adds).joinAll()
            // The collector records in order, so once the sentinel is in every new shot was recorded.
            // A lost update may drop the sentinel itself, so don't wait forever: the assertion says what's wrong.
            withTimeoutOrNull(WAIT) {
                repository.history.first { shots -> shots.any { it.eventId == shotId(sentinel) } }
            }

            assertThat(repository.history.value.map { it.eventId })
                .containsExactlyInAnyOrder(*(added + sentinel).map(::shotId).toTypedArray())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val ROUNDS = 200
        const val SEEDED = 40
        const val ADDED = 40
        val WAIT = 5.seconds
    }
}
