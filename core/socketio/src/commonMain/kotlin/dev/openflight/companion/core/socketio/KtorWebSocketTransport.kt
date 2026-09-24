// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * The Engine.IO WebSocket transport over Ktor, used without a polling handshake: the OpenFlight
 * server (Flask-SocketIO in `threading` mode with `simple-websocket` installed) accepts
 * `transport=websocket` directly, verified live against `openflight-server --mock`.
 *
 * [httpClient] is the app's shared client (OkHttp on Android, Darwin on iOS; both support
 * WebSockets). A derived client with the [WebSockets] plugin shares its engine.
 */
class KtorWebSocketTransport(
    httpClient: HttpClient,
) : EngineIoTransport {
    private val client = httpClient.config { install(WebSockets) }

    override suspend fun open(url: String): EngineIoConnection = KtorWebSocketConnection(client.webSocketSession(url))

    /** Builds the Engine.IO WebSocket URL (`ws[s]://host:port/socket.io/?EIO=4&transport=websocket`). */
    companion object {
        fun url(
            httpBaseUrl: String,
            path: String = DEFAULT_PATH,
        ): String {
            val base = httpBaseUrl.trimEnd('/')
            val wsBase =
                when {
                    base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                    base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                    else -> base
                }
            val normalizedPath = "/" + path.trim('/') + "/"
            return "$wsBase$normalizedPath?EIO=4&transport=websocket"
        }

        const val DEFAULT_PATH: String = "/socket.io/"
    }
}

private class KtorWebSocketConnection(
    private val session: DefaultClientWebSocketSession,
) : EngineIoConnection {
    private val frames = Channel<String>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<String> = frames

    init {
        session.launch {
            var failure: Throwable? = null
            try {
                for (frame in session.incoming) {
                    if (frame is Frame.Text) frames.send(frame.readText())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                failure = error
            } finally {
                frames.close(failure)
            }
        }
    }

    override suspend fun send(frame: String) {
        session.send(Frame.Text(frame))
    }

    override suspend fun close() {
        frames.close()
        session.close()
    }
}
