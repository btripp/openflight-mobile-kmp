// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
import dev.openflight.companion.core.network.EndpointUrl
import dev.openflight.companion.core.network.PiCameraClient
import dev.openflight.companion.core.socketio.EngineIoTransport
import dev.openflight.companion.core.socketio.KtorWebSocketTransport
import dev.openflight.companion.core.socketio.SocketConnectionState
import dev.openflight.companion.core.socketio.SocketEvent
import dev.openflight.companion.core.socketio.SocketIoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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

/** The Pi's camera HTTP API on one host (a seam for tests). */
internal interface PiCameraSource {
    suspend fun preview(host: String): CameraPreview

    suspend fun prepareReplay(
        host: String,
        replayId: String,
    ): CameraReplay

    /** The absolute URL of [replay]'s MP4 on [host], or `null`. */
    fun videoUrl(
        host: String,
        replay: CameraReplay,
    ): String?
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

/** [PiCameraSource] over [PiCameraClient]. */
internal class KtorPiCameraSource(
    private val client: PiCameraClient,
) : PiCameraSource {
    override suspend fun preview(host: String): CameraPreview = client.preview(host)

    override suspend fun prepareReplay(
        host: String,
        replayId: String,
    ): CameraReplay = client.prepareReplay(host, replayId)

    override fun videoUrl(
        host: String,
        replay: CameraReplay,
    ): String? = client.videoUrl(host, replay)
}
