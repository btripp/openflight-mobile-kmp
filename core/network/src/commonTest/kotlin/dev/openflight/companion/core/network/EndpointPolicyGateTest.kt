// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Plan R8d: a public cleartext address never reaches the HTTP engine, whichever transport is asked
 * (runs on the Android host too, where the app-wide cleartext `base-config` would otherwise let it
 * through). The engine counts every request it's handed.
 */
class EndpointPolicyGateTest {
    private var requests = 0
    private val engine =
        MockEngine {
            requests++
            respond(content = "")
        }

    private suspend fun assertRefusedWithoutARequest(call: suspend () -> Unit) {
        val error = runCatching { call() }.exceptionOrNull()
        assertThat(error)
            .isNotNull()
            .isInstanceOf(OpenFlightHttpError.InvalidHost::class)
            .prop(OpenFlightHttpError.InvalidHost::reason)
            .isEqualTo(EndpointDecision.CleartextToPublicHost(PUBLIC_IP).reason)
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun sseStreamReportsTheRejectionWithoutARequest() =
        runTest {
            val transport = wifiShotTransport(engine, testScheduler, host = PUBLIC_HTTP)
            transport.state.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                transport.start()
                assertThat(awaitItem()).isEqualTo(
                    ConnectionState.Error(
                        EndpointDecision.CleartextToPublicHost(PUBLIC_IP).reason,
                        ConnectionErrorKind.ENDPOINT_REJECTED,
                    ),
                )
                cancelAndIgnoreRemainingEvents()
            }
            testScheduler.advanceUntilIdle()
            assertThat(requests).isEqualTo(0)
            transport.disconnect()
        }

    @Test
    fun sseControlCallsThrowWithoutARequest() =
        runTest {
            val transport = wifiShotTransport(engine, testScheduler, host = PUBLIC_HTTP)
            assertRefusedWithoutARequest { transport.setClub(GolfClub.DRIVER) }
            assertRefusedWithoutARequest { transport.currentClub() }
            assertRefusedWithoutARequest { transport.submitCalibration(sampleMeasurement()) }
        }

    @Test
    fun shutdownThrowsWithoutARequest() =
        runTest {
            val client = PiControlClient(HttpClient(engine) { installOpenFlightDefaults() })
            assertRefusedWithoutARequest { client.shutdown(PUBLIC_HTTP) }
        }

    @Test
    fun cameraCallsThrowWithoutARequest() =
        runTest {
            val client = PiCameraClient(HttpClient(engine) { installOpenFlightDefaults() })
            assertRefusedWithoutARequest { client.preview(PUBLIC_HTTP) }
            assertRefusedWithoutARequest { client.prepareReplay(PUBLIC_HTTP, "abc123") }
        }

    @Test
    fun aRefusedHostBuildsNoUrl() {
        assertThat(EndpointUrl.build(PUBLIC_HTTP, "/api/shutdown")).isNull()
    }

    @Test
    fun theSameHostOverHttpsIsAllowedThrough() =
        runTest {
            val client = PiControlClient(HttpClient(engine) { installOpenFlightDefaults() })
            runCatching { client.shutdown("https://$PUBLIC_IP") }
            assertThat(requests).isEqualTo(1)
        }

    private companion object {
        const val PUBLIC_IP = "8.8.8.8"
        const val PUBLIC_HTTP = "http://$PUBLIC_IP"
    }
}
