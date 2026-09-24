// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.network.EndpointUrl
import dev.openflight.companion.core.socketio.EngineIoTransport
import dev.openflight.companion.core.socketio.KtorWebSocketTransport
import dev.openflight.companion.core.socketio.MjpegParser
import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketIoClient
import dev.openflight.companion.core.socketio.mjpegBoundary
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonElement

/** The Socket.IO connection [DefaultPiSessionRepository] drives (a seam for tests). */
internal interface PiSocket {
    val state: StateFlow<SocketConnectionState>
    val events: Flow<SocketEvent>

    fun connect()

    fun disconnect()

    suspend fun emit(
        event: String,
        data: JsonElement? = null,
    )
}

/** Builds the socket for one host, running in [scope]; `null` when the host can't be used. */
internal fun interface PiSocketFactory {
    fun create(
        host: String,
        scope: CoroutineScope,
    ): PiSocket?
}

/** Opens `GET /camera/stream` on one host as JPEG frames. */
internal fun interface PiCameraSource {
    fun frames(host: String): Flow<ByteArray>
}

internal class SocketIoPiSocket(
    private val client: SocketIoClient,
) : PiSocket {
    override val state: StateFlow<SocketConnectionState> = client.state
    override val events: Flow<SocketEvent> = client.events

    override fun connect() = client.connect()

    override fun disconnect() = client.disconnect()

    override suspend fun emit(
        event: String,
        data: JsonElement?,
    ) = client.emit(event, data)
}

/**
 * The Socket.IO endpoint shares the HTTP API's host and port (Flask-SocketIO serves both), so the
 * host goes through the same [EndpointUrl] normalization as the SSE stream.
 */
internal fun socketIoPiSocketFactory(transport: EngineIoTransport): PiSocketFactory =
    PiSocketFactory { host, scope ->
        EndpointUrl.build(host, "/")?.let { base ->
            SocketIoPiSocket(
                SocketIoClient(url = KtorWebSocketTransport.url(base), transport = transport, scope = scope),
            )
        }
    }

/** [PiCameraSource] over [httpClient], parsing the multipart body with [MjpegParser]. */
internal class KtorPiCameraSource(
    private val httpClient: HttpClient,
) : PiCameraSource {
    override fun frames(host: String): Flow<ByteArray> =
        channelFlow {
            val url =
                EndpointUrl.build(host, CAMERA_STREAM_PATH)
                    ?: throw WifiOnlyFeatureException(WifiOnlyFeatureException.Reason.NOT_CONNECTED)
            httpClient
                .prepareGet(url) {
                    // The stream ends only when streaming stops.
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw PiCameraUnavailableException(
                            response.status.value,
                            response.bodyAsText().ifBlank { response.status.description },
                        )
                    }
                    val boundary = response.headers[HttpHeaders.ContentType]?.let(::mjpegBoundary) ?: DEFAULT_BOUNDARY
                    val parser = MjpegParser(boundary)
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) parser.feed(buffer.copyOf(read)).forEach { send(it) }
                    }
                }
        }

    private companion object {
        const val CAMERA_STREAM_PATH = "/camera/stream"
        const val DEFAULT_BOUNDARY = "frame"
        const val READ_BUFFER_BYTES = 16 * 1024
    }
}
