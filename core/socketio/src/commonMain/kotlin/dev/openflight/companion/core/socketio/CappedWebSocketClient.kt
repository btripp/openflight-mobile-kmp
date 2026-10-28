// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import io.ktor.client.HttpClient

/** Largest incoming WebSocket frame accepted: python-engineio's `max_http_buffer_size` default. */
internal const val MAX_FRAME_BYTES: Long = 1_000_000

/**
 * A client with the WebSockets plugin whose engine refuses an incoming frame over
 * [MAX_FRAME_BYTES] (issue #166), so a hostile or broken LAN host can't make the app buffer one
 * huge frame.
 */
internal expect fun cappedWebSocketClient(httpClient: HttpClient): HttpClient
