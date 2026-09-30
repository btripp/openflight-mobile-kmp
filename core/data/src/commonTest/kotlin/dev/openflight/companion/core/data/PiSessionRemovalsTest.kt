// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.inMemoryShotHistoryDatabaseBuilder
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.protocol.SchemaV2Event
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * #68, #74: rows that leave the Pi's session (a delete or a per-profile clear by any client, the
 * kiosk included) leave the phone's history and its stored history too, and nothing else does:
 * not a reconnect to a Pi that restarted with a fresh session, not a profile switch.
 */
class PiSessionRemovalsTest {
    private class Harness(
        scope: CoroutineScope,
    ) {
        val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = HOST)
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
        val stored =
            DefaultShotHistoryRepository(
                openDatabase = { inMemoryShotHistoryDatabaseBuilder().buildShotHistoryDatabase() },
                scope = scope,
                newSessionId = { "session-${++sessions}" },
                log = {},
            )
        val history = RecordingHistory(stored)
        val repository =
            DefaultShotRepository(
                settings = settings,
                bluetoothTransport = FakeShotTransport("ble"),
                wifiTransportFactory = { host -> FakeShotTransport("wifi($host)").also { wifiTransports += it } },
                scope = scope,
                piControl = fakePiControlClient(),
                piSession = piSession,
                persistentHistory = history,
            )

        val wifi: FakeShotTransport get() = wifiTransports.last()
        val socket: FakePiSocket get() = sockets.last()

        /** A stock Pi: `/api/shots/stream` answers 404, so the Socket.IO link carries the shots. */
        fun stockPiConnected(): FakePiSocket {
            wifi.state.value = ConnectionState.Error("no stream", ConnectionErrorKind.STREAM_UNAVAILABLE)
            return socket.apply {
                serverAcks()
                emitted.clear()
            }
        }

        /** The link drops (the phone locks, say) and comes back. */
        fun reconnect() {
            socket.state.value = SocketConnectionState.Reconnecting(1, 500, "Connection closed")
            socket.serverAcks()
        }

        fun shown(): List<String> = repository.history.value.map { it.timestamp }

        /** Every stored shot, in every session. */
        suspend fun allStored(): List<String> {
            stored.awaitWrites()
            return stored
                .sessions()
                .first()
                .flatMap { stored.shots(it.id).first() }
                .map { it.detail.timestamp }
        }
    }

    /** Counts every timestamp the repository asks the stored history to delete. */
    private class RecordingHistory(
        private val inner: ShotHistoryRepository,
    ) : ShotHistoryRepository by inner {
        val deleted = mutableListOf<String>()

        override fun deleteShot(timestamp: String) = deleteShots(listOf(timestamp))

        override fun deleteShots(timestamps: Collection<String>) {
            deleted += timestamps
            inner.deleteShots(timestamps)
        }
    }

    private fun runRemovalsTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val harness = Harness(backgroundScope)
            harness.repository.start()
            body(harness)
        }

    @Test
    fun aReconnectToAPiThatRestartedWithAFreshSessionKeepsHistory() =
        runRemovalsTest { h ->
            val socket = h.stockPiConnected()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // The Pi restarted: its new session is empty, which says nothing about the old one.
            h.reconnect()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)

            val both = arrayOf(PiFixtures.SECOND_SHOT_TIMESTAMP, PiFixtures.SHOT_TIMESTAMP)
            assertThat(h.shown()).containsExactly(*both)
            assertThat(h.history.deleted).isEmpty()

            // The new session is followed from there on: the kiosk deletes its first shot.
            socket.serverFrame(PiFixtures.shotFrame(1, NEW_SESSION_TIMESTAMP))
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)

            // Awaited last: waiting on the database moves the test off the test dispatcher.
            assertThat(h.shown()).containsExactly(*both)
            assertThat(h.history.deleted).containsExactly(NEW_SESSION_TIMESTAMP)
            assertThat(h.allStored()).containsExactlyInAnyOrder(*both)
        }

    @Test
    fun aKioskDeleteWhileThePhoneWasAwayIsAppliedOnReconnect() =
        runRemovalsTest { h ->
            val socket = h.stockPiConnected()
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // Shot #1 was deleted on the kiosk while the link was down; the Pi kept shot #2.
            h.reconnect()
            socket.serverFrame(PiFixtures.sessionStateFrame(PiFixtures.SECOND_SHOT_FRAME))

            assertThat(h.shown()).containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP)
            assertThat(h.allStored()).containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP)
        }

    @Test
    fun aProfileSwitchRemovesNothing() =
        runRemovalsTest { h ->
            val socket = h.stockPiConnected()
            socket.serverFrame(PiFixtures.CONNECT_PROFILES_FRAME)
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            socket.serverFrame(PiFixtures.PROFILES_AFTER_ADD_FRAME)
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)

            // set_active_profile answers with `profiles` only (server.py:1836); the session keeps both rows.
            socket.serverFrame(
                PiFixtures.PROFILES_AFTER_ADD_FRAME.replace(
                    "\"active_profile_id\":\"${PiFixtures.SAM_PROFILE_ID}\"",
                    "\"active_profile_id\":\"${PiFixtures.DEFAULT_PROFILE_ID}\"",
                ),
            )
            socket.serverFrame(PiFixtures.sessionStateFrame(PiFixtures.SHOT_FRAME, PiFixtures.SECOND_SHOT_FRAME))

            assertThat(h.shown()).containsExactly(PiFixtures.SECOND_SHOT_TIMESTAMP, PiFixtures.SHOT_TIMESTAMP)
            assertThat(h.history.deleted).isEmpty()
        }

    // #74: the stored rows go by the Pi's whole session, not by its newest 200 or this connection's shots.
    @Test
    fun aClearRemovesEveryStoredShotOfThatProfileBeyondTheNewest200AndAcrossReconnects() =
        runRemovalsTest { h ->
            val socket = h.stockPiConnected()
            socket.serverFrame(PiFixtures.CONNECT_PROFILES_FRAME)
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            val early = (1..EARLY_SHOTS).map { PiFixtures.shotFrame(it, timestamp(it)) }
            early.forEach(socket::serverFrame)
            // A reconnect files the next shots under a new stored session.
            h.reconnect()
            socket.serverFrame(PiFixtures.sessionStateFrame(*early.toTypedArray()))
            (EARLY_SHOTS + 1..TOTAL_SHOTS).forEach { socket.serverFrame(PiFixtures.shotFrame(it, timestamp(it))) }

            // No awaiting in between: waiting on the database lets virtual time run past the clear timeout.
            h.repository.clearHistory()
            socket.serverFrame(PiFixtures.sessionClearedFrame(PiFixtures.DEFAULT_PROFILE_ID))

            assertThat(h.shown()).isEmpty()
            assertThat(h.allStored()).isEmpty()
        }

    // The fork sends each kiosk delete and clear twice: over Socket.IO and as a schema v2 event.
    @Test
    fun onTheForkAKioskDeleteAndClearAreEachStoredOnceWhicheverArrivesFirst() =
        runRemovalsTest { h ->
            h.wifi.state.value = ConnectionState.Connected
            val socket =
                h.socket.apply {
                    serverAcks()
                    emitted.clear()
                }
            socket.serverFrame(PiFixtures.CONNECT_SESSION_STATE_FRAME)
            socket.serverFrame(PiFixtures.SHOT_FRAME)
            h.wifi.shots.emit(sseShot(1, PiFixtures.SHOT_TIMESTAMP, PiFixtures.DEFAULT_PROFILE_ID))
            socket.serverFrame(PiFixtures.SECOND_SHOT_FRAME)
            h.wifi.shots.emit(sseShot(2, PiFixtures.SECOND_SHOT_TIMESTAMP, PiFixtures.SAM_PROFILE_ID))

            // Delete of shot #1: the SSE event first, then Socket.IO's session_state.
            h.wifi.schemaEvents.emit(SchemaV2Event.ShotDeleted(PiFixtures.SHOT_TIMESTAMP))
            socket.serverFrame(PiFixtures.sessionStateFrame(PiFixtures.SECOND_SHOT_FRAME))
            // Clear of Sam (shot #2): Socket.IO's session_cleared first, then the SSE event.
            socket.serverFrame(PiFixtures.sessionClearedFrame(PiFixtures.SAM_PROFILE_ID))
            h.wifi.schemaEvents.emit(SchemaV2Event.SessionCleared(PiFixtures.SAM_PROFILE_ID))

            assertThat(h.shown()).isEmpty()
            assertThat(h.allStored()).isEmpty()
            assertThat(h.history.deleted)
                .containsExactly(PiFixtures.SHOT_TIMESTAMP, PiFixtures.SECOND_SHOT_TIMESTAMP)
        }

    private companion object {
        const val HOST = "pi.local:8080"
        const val NEW_SESSION_TIMESTAMP = "2026-09-26T08:00:00.000001"
        const val EARLY_SHOTS = 105
        const val TOTAL_SHOTS = 205

        fun timestamp(number: Int) = "2026-09-25T11:00:00.${number.toString().padStart(6, '0')}"

        /** The fork's SSE `?schema=2` event for a shot the Socket.IO link also reports. */
        fun sseShot(
            number: Int,
            timestamp: String,
            profileId: String,
        ) = ShotEvent(
            schemaVersion = 2,
            eventId = stableShotEventId(timestamp, number),
            timestamp = timestamp,
            club = "driver",
            ballSpeedMph = 116.2,
            estimatedCarryYards = 179.0,
            type = "shot",
            final = true,
            shotNumber = number,
            profileId = profileId,
        )
    }
}
