// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
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

/** Plan R8d in the Pi session: the endpoint policy and the local-network flag. */
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
}
