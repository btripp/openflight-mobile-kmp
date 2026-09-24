// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketNotConnectedException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement

/** A scripted [PiSocket]: tests drive its state and events, and read what the repository emitted. */
internal class FakePiSocket(
    val host: String,
) : PiSocket {
    override val state = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Idle)
    override val events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 64)
    val emitted = mutableListOf<Pair<String, JsonElement?>>()
    var connectCount = 0
        private set
    var disconnectCount = 0
        private set

    override fun connect() {
        connectCount++
        state.value = SocketConnectionState.Connecting(1)
    }

    override fun disconnect() {
        disconnectCount++
        state.value = SocketConnectionState.Idle
    }

    override suspend fun emit(
        event: String,
        data: JsonElement?,
    ) {
        if (state.value !is SocketConnectionState.Connected) throw SocketNotConnectedException()
        emitted += event to data
    }

    fun serverAcks() {
        state.value = SocketConnectionState.Connected("sid")
    }

    /** Pushes a captured `42[...]` frame. */
    fun serverFrame(frame: String) {
        events.tryEmit(PiFixtures.event(frame))
    }

    fun server(
        name: String,
        payload: String? = null,
    ) {
        events.tryEmit(SocketEvent(name, listOfNotNull(payload?.let(PiJson::parseToJsonElement))))
    }

    val emittedNames: List<String> get() = emitted.map { it.first }
}
