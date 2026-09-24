// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import app.cash.turbine.test
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class SocketIoClientTest {
    private class FakeConnection : EngineIoConnection {
        private val channel = Channel<String>(Channel.UNLIMITED)
        override val incoming = channel
        val sent = mutableListOf<String>()
        var closed = false
            private set

        fun server(frame: String) {
            channel.trySend(frame)
        }

        fun serverCloses(cause: Throwable? = null) {
            channel.close(cause)
        }

        override suspend fun send(frame: String) {
            check(!closed) { "send after close" }
            sent += frame
        }

        override suspend fun close() {
            closed = true
            channel.close()
        }
    }

    private class FakeTransport : EngineIoTransport {
        val urls = mutableListOf<String>()
        val connections = mutableListOf<FakeConnection>()
        val failures = ArrayDeque<Throwable>()

        val last: FakeConnection get() = connections.last()

        override suspend fun open(url: String): EngineIoConnection {
            urls += url
            failures.removeFirstOrNull()?.let { throw it }
            return FakeConnection().also { connections += it }
        }
    }

    private data class Harness(
        val transport: FakeTransport,
        val client: SocketIoClient,
    )

    private fun runClientTest(body: suspend TestScope.(Harness) -> Unit) =
        runTest {
            val transport = FakeTransport()
            val harness = Harness(transport, SocketIoClient(url = URL, transport = transport, scope = backgroundScope))
            body(harness)
            harness.client.disconnect()
        }

    /** Drives one connection through the open packet and the Socket.IO connect ack. */
    private fun TestScope.handshake(
        transport: FakeTransport,
        sid: String = "sio-sid",
    ) {
        runCurrent()
        transport.last.server(OPEN)
        runCurrent()
        transport.last.server("""40{"sid":"$sid"}""")
        runCurrent()
    }

    @Test
    fun connectsSendsTheNamespaceConnectAndBecomesConnectedOnTheAck() =
        runClientTest { (transport, client) ->
            client.connect()
            runCurrent()
            assertThat(client.state.value).isEqualTo(SocketConnectionState.Connecting(attempt = 1))
            assertThat(transport.urls).containsExactly(URL)

            transport.last.server(OPEN)
            runCurrent()
            assertThat(transport.last.sent).containsExactly("40")

            transport.last.server("""40{"sid":"n1hTinl8D9RUgCmEAAAD"}""")
            runCurrent()
            assertThat(client.state.value).isEqualTo(SocketConnectionState.Connected("n1hTinl8D9RUgCmEAAAD"))
        }

    @Test
    fun deliversEventsIncludingThoseTheServerSendsBeforeTheConnectAck() =
        runClientTest { (transport, client) ->
            client.events.test {
                client.connect()
                runCurrent()
                transport.last.server(OPEN)
                // Flask-SocketIO's connect handler broadcasts before the ack reaches the client.
                transport.last.server("""42["club_changed",{"club":"driver"}]""")
                transport.last.server("""40{"sid":"x"}""")
                transport.last.server("""42["session_cleared"]""")
                runCurrent()

                assertThat(awaitItem()).isEqualTo(
                    SocketEvent("club_changed", listOf(buildJsonObject { put("club", "driver") })),
                )
                assertThat(awaitItem()).isEqualTo(SocketEvent("session_cleared"))
            }
        }

    @Test
    fun answersEveryServerPingWithAPong() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)

            transport.last.server("2")
            runCurrent()

            assertThat(transport.last.sent).containsExactly("40", "3")
        }

    @Test
    fun emitsEventsAsEngineIoMessagesOnceConnected() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)

            client.emit("simulate_shot")
            client.emit("delete_shot", buildJsonObject { put("timestamp", "2026-09-24T15:38:33.264795") })

            assertThat(transport.last.sent).containsExactly(
                "40",
                """42["simulate_shot"]""",
                """42["delete_shot",{"timestamp":"2026-09-24T15:38:33.264795"}]""",
            )
        }

    @Test
    fun emitFailsWhileNotConnected() =
        runClientTest { (transport, client) ->
            assertFailure { client.emit("simulate_shot") }.isInstanceOf(SocketNotConnectedException::class)

            client.connect()
            runCurrent()
            transport.last.server(OPEN)
            runCurrent()
            // Opened but no connect ack yet.
            assertFailure { client.emit("simulate_shot") }.isInstanceOf(SocketNotConnectedException::class)
        }

    @Test
    fun reconnectsWithDoublingBackoffCappedAtFifteenSecondsAfterFailedOpens() =
        runClientTest { (transport, client) ->
            repeat(6) { transport.failures += IllegalStateException("refused") }
            client.connect()
            runCurrent()
            assertThat(client.state.value).isEqualTo(
                SocketConnectionState.Reconnecting(attempt = 1, retryInMillis = 1_000, reason = "refused"),
            )

            val observedDelays = mutableListOf<Long>()
            repeat(5) {
                val reconnecting = client.state.value as SocketConnectionState.Reconnecting
                observedDelays += reconnecting.retryInMillis
                advanceTimeBy(reconnecting.retryInMillis)
                runCurrent()
            }
            observedDelays += (client.state.value as SocketConnectionState.Reconnecting).retryInMillis

            assertThat(observedDelays).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L)
            assertThat(transport.urls).hasSize(6)
        }

    @Test
    fun aSuccessfulConnectResetsTheBackoff() =
        runClientTest { (transport, client) ->
            repeat(2) { transport.failures += IllegalStateException("refused") }
            client.connect()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            advanceTimeBy(2_000)
            handshake(transport)
            assertThat(client.state.value).isInstanceOf(SocketConnectionState.Connected::class)

            transport.last.serverCloses()
            runCurrent()

            assertThat(client.state.value).isEqualTo(
                SocketConnectionState.Reconnecting(attempt = 1, retryInMillis = 1_000, reason = "Connection closed"),
            )
        }

    @Test
    fun treatsMissingPingsAsADeadConnectionAndReconnects() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)
            val first = transport.last

            // pingInterval 25 s + pingTimeout 20 s with no packet from the server.
            advanceTimeBy(45_000)
            runCurrent()

            assertThat(client.state.value).isEqualTo(
                SocketConnectionState.Reconnecting(attempt = 1, retryInMillis = 1_000, reason = "Heartbeat timeout"),
            )
            assertThat(first.closed).isTrue()

            advanceTimeBy(1_000)
            handshake(transport, sid = "second")
            assertThat(transport.connections).hasSize(2)
            assertThat(client.state.value).isEqualTo(SocketConnectionState.Connected("second"))
        }

    @Test
    fun pingsKeepTheConnectionAlive() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)

            repeat(4) {
                advanceTimeBy(25_000)
                transport.last.server("2")
                runCurrent()
            }

            assertThat(client.state.value).isInstanceOf(SocketConnectionState.Connected::class)
            assertThat(transport.connections).hasSize(1)
        }

    @Test
    fun anEngineIoCloseFromTheServerTriggersAReconnect() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)

            transport.last.server("1")
            runCurrent()

            assertThat(client.state.value).isInstanceOf(SocketConnectionState.Reconnecting::class)
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(transport.connections).hasSize(2)
        }

    @Test
    fun aConnectErrorIsReportedAsTheReconnectReason() =
        runClientTest { (transport, client) ->
            client.connect()
            runCurrent()
            transport.last.server(OPEN)
            transport.last.server("""44{"message":"Not authorized"}""")
            runCurrent()

            assertThat(client.state.value).isEqualTo(
                SocketConnectionState.Reconnecting(attempt = 1, retryInMillis = 1_000, reason = "Not authorized"),
            )
        }

    @Test
    fun disconnectSendsTheSocketIoDisconnectClosesAndGoesIdle() =
        runClientTest { (transport, client) ->
            client.connect()
            handshake(transport)
            val connection = transport.last

            client.disconnect()
            runCurrent()

            assertThat(connection.sent).containsExactly("40", "41")
            assertThat(connection.closed).isTrue()
            assertThat(client.state.value).isEqualTo(SocketConnectionState.Idle)
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(transport.connections).hasSize(1)
        }

    @Test
    fun connectIsIdempotentWhileRunning() =
        runClientTest { (transport, client) ->
            client.connect()
            client.connect()
            runCurrent()

            assertThat(transport.urls).hasSize(1)
            assertThat(transport.last.closed).isFalse()
        }

    private companion object {
        const val URL = "ws://pi.local:8080/socket.io/?EIO=4&transport=websocket"
        const val OPEN =
            """0{"sid":"eio-sid","upgrades":[],"pingTimeout":20000,"pingInterval":25000,"maxPayload":1000000}"""
    }
}
