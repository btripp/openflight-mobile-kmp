// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reconnect backoff: [initialMillis], doubling per failed attempt, capped at [maxMillis]. Resets on connect. */
data class ReconnectPolicy(
    val initialMillis: Long = 1_000,
    val maxMillis: Long = 15_000,
) {
    /** The delay before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        var delay = initialMillis
        repeat(attempt - 1) { delay = (delay * 2).coerceAtMost(maxMillis) }
        return delay.coerceAtMost(maxMillis)
    }
}

/**
 * A minimal Socket.IO v5 client (Engine.IO v4) for the default namespace, text packets only.
 *
 * [connect] starts a loop that, per attempt: opens [transport] at [url], waits for the Engine.IO
 * open packet, sends the Socket.IO connect (`40`), then answers pings (`2` → `3`) and publishes
 * every event on [events] until the connection ends. The connection counts as dead when no packet
 * arrives within `pingInterval + pingTimeout` (the server pings every `pingInterval`). Any failure
 * or close schedules the next attempt with [reconnectPolicy]; a connect ack resets it.
 *
 * Events are published whether or not the connect ack has arrived: Flask-SocketIO's `connect`
 * handler broadcasts (`club_changed`, `session_state`, ...) before the ack reaches the client.
 */
class SocketIoClient(
    private val url: String,
    private val transport: EngineIoTransport,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val openTimeoutMillis: Long = DEFAULT_OPEN_TIMEOUT_MS,
) {
    private val mutableState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Idle)
    private val mutableEvents = MutableSharedFlow<SocketEvent>(extraBufferCapacity = EVENT_BUFFER)

    val state: StateFlow<SocketConnectionState> = mutableState.asStateFlow()

    /** Server events, hot: collect before [connect] to see the ones sent on connect. */
    val events: SharedFlow<SocketEvent> = mutableEvents.asSharedFlow()

    private var loopJob: Job? = null

    /** The connection once the Socket.IO connect ack has arrived, else `null`. */
    private var connected: EngineIoConnection? = null

    /** Starts connecting (and reconnecting) until [disconnect]. Idempotent. */
    fun connect() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    /** Sends the Socket.IO disconnect, closes the connection and stops reconnecting. */
    fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        connected = null
        mutableState.value = SocketConnectionState.Idle
    }

    /**
     * Emits `42["event", data]` (or `42["event"]` when [data] is `null`).
     *
     * @throws SocketNotConnectedException unless [state] is [SocketConnectionState.Connected].
     */
    suspend fun emit(
        event: String,
        data: JsonElement? = null,
    ) {
        val connection = connected ?: throw SocketNotConnectedException()
        val packet = SocketIoPacket.Event(name = event, args = listOfNotNull(data))
        connection.send(encodeMessage(packet))
    }

    @Suppress("TooGenericExceptionCaught") // Any transport failure means "reconnect", never a crash.
    private suspend fun runLoop() {
        var attempt = 1
        while (true) {
            mutableState.value = SocketConnectionState.Connecting(attempt)
            val reason =
                try {
                    var wasConnected = false
                    val reason = runConnection { wasConnected = true }
                    if (wasConnected) attempt = 1
                    reason
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    error.message ?: error.toString()
                }
            connected = null
            val retryIn = reconnectPolicy.delayFor(attempt)
            mutableState.value = SocketConnectionState.Reconnecting(attempt, retryIn, reason)
            delay(retryIn)
            attempt++
        }
    }

    /** Runs one connection to its end and returns why it ended. */
    private suspend fun runConnection(onConnected: () -> Unit): String {
        val connection = withTimeout(openTimeoutMillis) { transport.open(url) }
        var ackReceived = false
        try {
            val handshake = withTimeout(openTimeoutMillis) { awaitOpen(connection) } ?: return CLOSED
            connection.send(encodeMessage(SocketIoPacket.Connect()))
            return pump(connection, silenceLimit = handshake.pingInterval + handshake.pingTimeout) { sid ->
                ackReceived = true
                connected = connection
                mutableState.value = SocketConnectionState.Connected(sid)
                onConnected()
            }
        } finally {
            connected = null
            // Say goodbye only when we are the ones leaving (disconnect()), not after the server left.
            val leaving = !currentCoroutineContext().isActive
            withContext(NonCancellable) { closeQuietly(connection, sendDisconnect = ackReceived && leaving) }
        }
    }

    /** Reads packets until the connection ends; returns the reason. */
    @Suppress("ReturnCount") // Each return is one way a connection ends.
    private suspend fun pump(
        connection: EngineIoConnection,
        silenceLimit: Long,
        onAck: (sid: String?) -> Unit,
    ): String {
        while (true) {
            val frame =
                withTimeoutOrNull(silenceLimit) { connection.incoming.receiveCatching() } ?: return HEARTBEAT_TIMEOUT
            if (frame.isClosed) return frame.exceptionOrNull()?.let { it.message ?: it.toString() } ?: CLOSED
            val packet = EngineIoCodec.decodePacket(frame.getOrThrow()) ?: continue
            val end =
                when (packet) {
                    is EngineIoPacket.Ping -> {
                        connection.send(EngineIoCodec.encodePacket(EngineIoPacket.Pong(packet.data)))
                        null
                    }

                    is EngineIoPacket.Message -> {
                        handleMessage(packet.data, onAck)
                    }

                    EngineIoPacket.Close -> {
                        CLOSED
                    }

                    else -> {
                        null
                    }
                }
            if (end != null) return end
        }
    }

    private suspend fun awaitOpen(connection: EngineIoConnection): EngineIoHandshake? {
        while (true) {
            val frame = connection.incoming.receiveCatching()
            if (frame.isClosed) return frame.exceptionOrNull()?.let { throw it }
            val packet = EngineIoCodec.decodePacket(frame.getOrThrow())
            if (packet is EngineIoPacket.Open) return packet.handshake
        }
    }

    /** Handles one Socket.IO packet; returns a reason when it ends the connection. */
    private suspend fun handleMessage(
        data: String,
        onAck: (sid: String?) -> Unit,
    ): String? {
        val packet = SocketIoCodec.decode(data)?.takeIf { it.namespace == SocketIoPacket.DEFAULT_NAMESPACE }
        return when (packet) {
            is SocketIoPacket.Connect -> {
                onAck((packet.data?.get("sid") as? JsonPrimitive)?.contentOrNull)
                null
            }

            is SocketIoPacket.Event -> {
                mutableEvents.emit(SocketEvent(packet.name, packet.args))
                null
            }

            is SocketIoPacket.Disconnect -> {
                "Disconnected by server"
            }

            is SocketIoPacket.ConnectError -> {
                packet.message ?: "Connect error"
            }

            is SocketIoPacket.Ack, null -> {
                null
            }
        }
    }

    private fun encodeMessage(packet: SocketIoPacket): String =
        EngineIoCodec.encodePacket(EngineIoPacket.Message(SocketIoCodec.encode(packet)))

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Closing a dead socket may throw; nothing to do.
    private suspend fun closeQuietly(
        connection: EngineIoConnection,
        sendDisconnect: Boolean,
    ) {
        try {
            if (sendDisconnect) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { connection.send(encodeMessage(SocketIoPacket.Disconnect())) }
            }
        } catch (_: Exception) {
            // The peer is already gone.
        }
        try {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { connection.close() }
        } catch (_: Exception) {
            // The peer is already gone.
        }
    }

    private companion object {
        const val DEFAULT_OPEN_TIMEOUT_MS = 10_000L
        const val CLOSE_TIMEOUT_MS = 1_000L
        const val EVENT_BUFFER = 64
        const val CLOSED = "Connection closed"
        const val HEARTBEAT_TIMEOUT = "Heartbeat timeout"
    }
}
