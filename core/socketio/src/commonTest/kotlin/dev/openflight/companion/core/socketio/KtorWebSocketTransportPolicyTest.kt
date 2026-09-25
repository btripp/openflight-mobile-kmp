// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.openflight.companion.core.network.OpenFlightHttpError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Plan R8d: the Socket.IO WebSocket is refused before Ktor for an address the endpoint policy rejects. */
class KtorWebSocketTransportPolicyTest {
    @Test
    fun aPublicCleartextSocketNeverReachesTheEngine() =
        runTest {
            var requests = 0
            val transport =
                KtorWebSocketTransport(
                    HttpClient(
                        MockEngine {
                            requests++
                            respond("")
                        },
                    ),
                )

            assertFailure { transport.open(KtorWebSocketTransport.url("http://8.8.8.8:8080")) }
                .isInstanceOf(OpenFlightHttpError.InvalidHost::class)
            assertThat(requests).isEqualTo(0)
        }

    @Test
    fun wsUrlsAreJudgedAsTheirHttpEquivalents() {
        assertThat(KtorWebSocketTransport.httpEquivalent("ws://pi.local:8080/socket.io/"))
            .isEqualTo("http://pi.local:8080/socket.io/")
        assertThat(KtorWebSocketTransport.httpEquivalent("wss://pi.example/socket.io/"))
            .isEqualTo("https://pi.example/socket.io/")
    }
}
