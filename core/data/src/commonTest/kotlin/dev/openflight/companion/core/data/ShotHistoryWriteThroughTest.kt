// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.single
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.protocol.SchemaV2Event
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [DefaultShotRepository] writing through to the persistent history (plan R8h): the in-memory
 * `history` stays the current session's cache, and the stored history follows it.
 */
class ShotHistoryWriteThroughTest {
    private class Harness(
        scope: CoroutineScope,
        transport: TransportType,
    ) {
        val settings = FakeSettingsRepository(transport = transport, host = HOST)
        val ble = FakeShotTransport("ble")
        val wifiTransports = mutableListOf<FakeShotTransport>()
        val sockets = mutableListOf<FakePiSocket>()
        val piSession =
            DefaultPiSessionRepository(
                settings = settings,
                socketFactory = { socketHost, _ -> FakePiSocket(socketHost).also { sockets += it } },
                cameraSource = FakePiCameraSource(),
                scope = scope,
            )
        private var sessions = 0
        val history =
            DefaultShotHistoryRepository(
                openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
                scope = scope,
                newSessionId = { "session-${++sessions}" },
                log = {},
            )
        val repository =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = ble,
                wifiTransportFactory = { host -> FakeShotTransport("wifi($host)").also { wifiTransports += it } },
                scope = scope,
                piControl = fakePiControlClient(),
                piSession = piSession,
                persistentHistory = history,
            )

        val wifi: FakeShotTransport get() = wifiTransports.last()

        fun piConnected(): FakePiSocket =
            sockets.last().apply {
                serverAcks()
                emitted.clear()
            }

        /** The session shots are filed under now (the Pi link's connect starts one of its own). */
        fun current(): String = checkNotNull(history.currentSessionId.value)

        suspend fun storedTimestamps(sessionId: String = current()): List<String> {
            history.awaitWrites()
            return history.shots(sessionId).first().map { it.detail.timestamp }
        }
    }

    private fun runWriteThroughTest(
        transport: TransportType = TransportType.WIFI,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(backgroundScope, transport)
        harness.repository.start()
        body(harness)
    }

    @Test
    fun aConnectStartsASessionAndItsShotsAreStored() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.shots.emit(timedShot(1, "2026-09-25T10:00:00"))
            h.wifi.shots.emit(timedShot(2, "2026-09-25T10:01:00"))

            assertThat(h.storedTimestamps()).containsExactly("2026-09-25T10:01:00", "2026-09-25T10:00:00")
            assertThat(
                h.history
                    .sessions()
                    .first()
                    .single()
                    .host,
            ).isEqualTo(HOST)
        }

    @Test
    fun aReconnectStartsANewSessionButStatesWithinOneConnectionDoNot() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.shots.emit(timedShot(1, "2026-09-25T10:00:00"))
            h.wifi.state.value = ConnectionState.Connecting
            h.wifi.state.value = ConnectionState.Connected
            h.wifi.shots.emit(timedShot(2, "2026-09-25T10:05:00"))

            assertThat(h.storedTimestamps("session-1")).containsExactly("2026-09-25T10:00:00")
            assertThat(h.storedTimestamps("session-2")).containsExactly("2026-09-25T10:05:00")
            assertThat(
                h.history
                    .sessions()
                    .first()
                    .map { it.id },
            ).containsExactly("session-2", "session-1")
        }

    @Test
    fun aPiLinkConnectStartsASessionWithoutAnSseStream() =
        runWriteThroughTest { h ->
            // Backend main serves no SSE stream: only the Socket.IO link ever connects.
            h.piConnected().serverFrame(PiFixtures.SHOT_FRAME)
            h.history.awaitWrites()

            val session =
                h.history
                    .sessions()
                    .first()
                    .single()
            assertThat(session.transport).isEqualTo(TransportType.WIFI)
            assertThat(session.host).isEqualTo(HOST)
            assertThat(session.shotCount).isEqualTo(1)
        }

    @Test
    fun aPiLinkReconnectStartsANewSession() =
        runWriteThroughTest { h ->
            val socket = h.piConnected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.state.value = SocketConnectionState.Reconnecting(1, 500, "Connection closed")
            socket.serverAcks()
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
            h.history.awaitWrites()

            assertThat(
                h.history
                    .sessions()
                    .first()
                    .map { it.shotCount },
            ).containsExactly(1, 1)
        }

    @Test
    fun bluetoothShotsAreStoredToo() =
        runWriteThroughTest(transport = TransportType.BLUETOOTH) { h ->
            h.ble.state.value = ConnectionState.Connected
            h.ble.shots.emit(shot(1))

            h.history.awaitWrites()
            assertThat(
                h.history
                    .sessions()
                    .first()
                    .single()
                    .transport,
            ).isEqualTo(TransportType.BLUETOOTH)
        }

    @Test
    fun theSocketIoShotAndItsSseEventFileOneRowWithTheProfile() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            h.wifi.shots.emit(timedShot(1, PiFixtures.SHOT_TIMESTAMP))
            h.history.awaitWrites()

            val stored =
                h.history
                    .shots(h.current())
                    .first()
                    .single()
            assertThat(stored.eventId).isEqualTo(shotId(1))
            assertThat(stored.detail.shotNumber).isEqualTo(1)
            assertThat(stored.detail.profileName).isEqualTo("Profile 1")
        }

    @Test
    fun aShotUpdateEnrichesTheStoredShot() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SHOT_UPDATE_FRAME)
            h.history.awaitWrites()

            assertThat(h.history.shots(h.current()).first()).single().transform { it.detail.shotNumber }.isEqualTo(1)
        }

    @Test
    fun theSessionSnapshotOnConnectIsNotStored() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            h.piConnected().serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)
            h.history.awaitWrites()

            assertThat(h.history.sessions().first()).isEmpty()
        }

    @Test
    fun aDeleteThePiConfirmedIsMirrored() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            h.wifi.shots.emit(timedShot(1, PiFixtures.SHOT_TIMESTAMP))
            h.wifi.shots.emit(timedShot(2, "2026-09-24T15:39:00.000001"))

            // No awaiting in between: waiting on the database lets virtual time run on, and the
            // pending delete must still be the Pi's answer's.
            h.repository.deleteShot(shotId(1))
            socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            assertThat(h.storedTimestamps()).containsExactly("2026-09-24T15:39:00.000001")
        }

    @Test
    fun aPerProfileClearThePiConfirmedIsMirrored() =
        runWriteThroughTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            // Sam is active; shot #2 is Sam's, shot #1 the default profile's.
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // No awaiting in between: waiting on the database lets virtual time run past the
            // 10 s clear timeout.
            h.repository.clearHistory()
            socket.serverFrame(PiFixtures.SESSION_CLEARED_FRAME)

            assertThat(h.storedTimestamps()).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun aLocalDeleteIsMirroredButALocalClearLeavesTheStoredHistory() =
        runWriteThroughTest(transport = TransportType.BLUETOOTH) { h ->
            h.ble.state.value = ConnectionState.Connected
            h.ble.shots.emit(timedShot(1, "2026-09-25T10:00:00"))
            h.ble.shots.emit(timedShot(2, "2026-09-25T10:01:00"))
            h.ble.shots.emit(timedShot(3, "2026-09-25T10:02:00"))

            h.repository.deleteShot(shotId(1))
            h.repository.clearHistory()

            assertThat(h.repository.history.value).isEmpty()
            assertThat(h.storedTimestamps()).containsExactlyInAnyOrder("2026-09-25T10:01:00", "2026-09-25T10:02:00")
        }

    @Test
    fun aSchemaV2ShotDeletedIsMirrored() =
        runWriteThroughTest(transport = TransportType.BLUETOOTH) { h ->
            h.ble.state.value = ConnectionState.Connected
            h.ble.shots.emit(timedShot(1, "2026-09-25T10:00:00"))
            h.ble.shots.emit(timedShot(2, "2026-09-25T10:01:00"))

            h.ble.schemaEvents.emit(SchemaV2Event.ShotDeleted("2026-09-25T10:00:00"))

            assertThat(
                h.repository.history.value
                    .map { it.timestamp },
            ).containsExactly("2026-09-25T10:01:00")
            assertThat(h.storedTimestamps()).containsExactly("2026-09-25T10:01:00")
        }

    @Test
    fun aSchemaV2SessionClearedDropsThatProfilesStoredShotsOnly() =
        runWriteThroughTest(transport = TransportType.BLUETOOTH) { h ->
            h.ble.state.value = ConnectionState.Connected
            h.ble.shots.emit(timedShot(1, "2026-09-25T10:00:00").copy(profileId = "sam"))
            h.ble.shots.emit(timedShot(2, "2026-09-25T10:01:00").copy(profileId = "alex"))

            h.ble.schemaEvents.emit(SchemaV2Event.SessionCleared("sam"))

            assertThat(
                h.repository.history.value
                    .map { it.timestamp },
            ).containsExactly("2026-09-25T10:01:00")
            assertThat(h.storedTimestamps()).containsExactly("2026-09-25T10:01:00")
        }

    // Plan F3, A8: the Pi's deletes are about the phone's own sessions only.
    @Test
    fun anImportedSessionWithTheSameTimestampSurvivesAPiDeleteShot() =
        runWriteThroughTest { h ->
            // Queued first, awaited last: awaiting the database here would let virtual time run on.
            val imported = async { h.history.importSession(importedSession(PiFixtures.SHOT_TIMESTAMP)) }
            h.wifi.state.value = ConnectionState.Connected
            val socket = h.piConnected()
            h.wifi.shots.emit(timedShot(1, PiFixtures.SHOT_TIMESTAMP))

            h.repository.deleteShot(shotId(1))
            socket.serverFrame(PiFixtures.SESSION_STATE_AFTER_DELETE_FRAME)

            val current = h.current()
            val importedId = checkNotNull(imported.await())
            assertThat(h.storedTimestamps(current)).isEmpty()
            assertThat(h.storedTimestamps(importedId)).containsExactly(PiFixtures.SHOT_TIMESTAMP)
        }

    @Test
    fun anImportedSessionWithTheSameTimestampSurvivesASessionCleared() =
        runWriteThroughTest(transport = TransportType.BLUETOOTH) { h ->
            val imported = async { h.history.importSession(importedSession("2026-09-25T10:00:00")) }
            h.ble.state.value = ConnectionState.Connected
            h.ble.shots.emit(timedShot(1, "2026-09-25T10:00:00").copy(profileId = "sam"))

            h.ble.schemaEvents.emit(SchemaV2Event.SessionCleared("sam"))

            val current = h.current()
            val importedId = checkNotNull(imported.await())
            assertThat(h.storedTimestamps(current)).isEmpty()
            assertThat(h.storedTimestamps(importedId)).containsExactly("2026-09-25T10:00:00")
            assertThat(
                h.history
                    .sessions(includeImported = true)
                    .first()
                    .map { it.id },
            ).containsExactly(importedId)
        }

    private companion object {
        const val HOST = "pi.local:8080"

        fun importedSession(timestamp: String) =
            ImportedSession(
                ownerName = "Sam",
                title = null,
                startedAtEpochMillis = 1_000L,
                shots =
                    listOf(
                        ShotDetail(
                            timestamp = timestamp,
                            shotNumber = 1,
                            ballSpeedMph = 150.0,
                            club = "driver",
                        ),
                    ),
            )

        fun timedShot(
            number: Int,
            timestamp: String,
        ): ShotEvent = shot(number).copy(timestamp = timestamp)
    }
}
