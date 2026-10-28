// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Ignores [httpClient]: Ktor's OkHttp engine throws on any `maxFrameSize` and OkHttp has no
 * incoming-message limit of its own (issue #132), so the socket gets its own CIO client, which
 * enforces the cap. Redirects stay off, as in `installOpenFlightDefaults`, so a redirect can't
 * skip the endpoint policy.
 */
internal actual fun cappedWebSocketClient(httpClient: HttpClient): HttpClient =
    HttpClient(CIO) {
        followRedirects = false
        install(WebSockets) { maxFrameSize = MAX_FRAME_BYTES }
    }
