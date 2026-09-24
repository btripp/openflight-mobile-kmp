// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class KtorWebSocketTransportUrlTest {
    @Test
    fun switchesHttpToWsAndAddsTheEngineIoQuery() {
        assertThat(KtorWebSocketTransport.url("http://raspberrypi.local:8080"))
            .isEqualTo("ws://raspberrypi.local:8080/socket.io/?EIO=4&transport=websocket")
    }

    @Test
    fun switchesHttpsToWss() {
        assertThat(KtorWebSocketTransport.url("https://pi.example/"))
            .isEqualTo("wss://pi.example/socket.io/?EIO=4&transport=websocket")
    }

    @Test
    fun acceptsAPathWithOrWithoutSlashes() {
        assertThat(KtorWebSocketTransport.url("http://h:1", path = "socket.io"))
            .isEqualTo("ws://h:1/socket.io/?EIO=4&transport=websocket")
    }
}
