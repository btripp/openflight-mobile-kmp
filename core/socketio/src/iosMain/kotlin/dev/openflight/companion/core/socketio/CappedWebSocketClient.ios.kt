// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets

/** Darwin maps `maxFrameSize` to `NSURLSessionWebSocketTask.maximumMessageSize`; the engine is shared. */
internal actual fun cappedWebSocketClient(httpClient: HttpClient): HttpClient =
    httpClient.config { install(WebSockets) { maxFrameSize = MAX_FRAME_BYTES } }
