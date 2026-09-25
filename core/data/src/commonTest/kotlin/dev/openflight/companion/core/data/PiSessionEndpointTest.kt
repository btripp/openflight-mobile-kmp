// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.network.EndpointDecision
import dev.openflight.companion.core.network.LocalNetworkDenial
import dev.openflight.companion.core.socketio.EngineIoConnection
import dev.openflight.companion.core.socketio.EngineIoTransport
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Plan R8d in the Pi session: the endpoint policy, the local-network flag and the last-good host. */
class PiSessionEndpointTest {
    private class RecordingTransport : EngineIoTransport {
        val urls = mutableListOf<String>()

        override suspend fun open(url: String): EngineIoConnection {
            urls += url
            error("not connecting in this test")
        }
    }

    @Test
    fun aPublicCleartextHostIsRejectedWithItsReasonAndNothingOpens() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RecordingTransport()
            val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "http://8.8.8.8")
            val repository =
                DefaultPiSessionRepository(
                    settings = settings,
                    socketFactory = socketIoPiSocketFactory(transport),
                    cameraSource = FakePiCameraSource(),
                    scope = backgroundScope,
                )

            repository.start()

            assertThat(repository.linkState.value)
                .isEqualTo(PiLinkState.Rejected(EndpointDecision.CleartextToPublicHost("8.8.8.8").reason))
            assertThat(transport.urls).isEmpty()
            repository.stop()
        }

    @Test
    fun aLocalNetworkDenialIsCarriedIntoTheLinkState() =
        runPiTest { h ->
            h.socket.state.value =
                SocketConnectionState.Reconnecting(1, 500, LocalNetworkDenial.MESSAGE, localNetworkDenied = true)

            assertThat(h.repository.linkState.value)
                .isEqualTo(PiLinkState.Reconnecting(1, 500, LocalNetworkDenial.MESSAGE, localNetworkDenied = true))
        }

    @Test
    fun theHostIsRememberedOnlyOnceTheLinkConnects() =
        runPiTest(host = "192.168.1.100:8080") { h ->
            assertThat(h.settings.connectedHosts).isEmpty()
            h.drop()
            assertThat(h.settings.connectedHosts).isEmpty()

            h.socket.serverAcks()

            assertThat(h.settings.connectedHosts).containsExactly("192.168.1.100:8080")
        }

    @Test
    fun aHostThatNeverConnectsIsNeverRemembered() =
        runPiTest { h ->
            h.connected()
            h.settings.setHost("typo.local:8080")
            h.drop()

            assertThat(h.settings.connectedHosts).containsExactly("pi.local:8080")
        }
}
