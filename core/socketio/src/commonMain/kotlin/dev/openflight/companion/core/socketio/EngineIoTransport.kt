// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Opens one Engine.IO connection. [SocketIoClient] owns everything above the frame level
 * (handshake, heartbeats, Socket.IO packets, reconnects); a transport only moves text frames.
 */
fun interface EngineIoTransport {
    /**
     * Connects to [url] (a `ws://`/`wss://` Engine.IO endpoint including its `EIO=4&transport=`
     * query). Throws if the connection can't be opened.
     */
    suspend fun open(url: String): EngineIoConnection
}

/** One open connection: each text frame is one Engine.IO packet. */
interface EngineIoConnection {
    /** Incoming text frames. Closes normally when the peer closes, or with the failure as its cause. */
    val incoming: ReceiveChannel<String>

    suspend fun send(frame: String)

    /** Closes the connection. Idempotent. */
    suspend fun close()
}
