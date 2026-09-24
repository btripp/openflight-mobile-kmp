// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.serialization.json.JsonElement

/** A server-sent Socket.IO event: `42["name", arg0, arg1, ...]`. */
data class SocketEvent(
    val name: String,
    val args: List<JsonElement> = emptyList(),
) {
    /** The first argument, which is the whole payload for every OpenFlight event (or `null` for none). */
    val data: JsonElement? get() = args.firstOrNull()
}

/** Connection state of a [SocketIoClient]. */
sealed interface SocketConnectionState {
    /** Not started, or stopped. */
    data object Idle : SocketConnectionState

    /** Opening the transport and waiting for the Socket.IO connect ack. */
    data class Connecting(
        val attempt: Int,
    ) : SocketConnectionState

    /** The server acknowledged the Socket.IO connect; [emit][SocketIoClient.emit] works. */
    data class Connected(
        val sid: String?,
    ) : SocketConnectionState

    /** The last attempt failed or the connection dropped; the next attempt starts in [retryInMillis]. */
    data class Reconnecting(
        val attempt: Int,
        val retryInMillis: Long,
        val reason: String,
    ) : SocketConnectionState
}

/** [SocketIoClient.emit] was called while not [SocketConnectionState.Connected]. */
class SocketNotConnectedException : IllegalStateException("Socket.IO is not connected.")
