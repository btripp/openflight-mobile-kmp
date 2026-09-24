// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val EVENT_ID_1 = "11111111-1111-1111-1111-111111111111"
private const val EVENT_ID_2 = "22222222-2222-2222-2222-222222222222"
private const val EVENT_ID_3 = "33333333-3333-3333-3333-333333333333"

/**
 * Ported behaviour from `WiFiShotClientTests` plus the Step 4 task list: decoding, replay
 * suppression across an *automatic* reconnect (and its restoration after an explicit [retry]),
 * decode errors, the 503 "too many devices" status, an invalid host, and cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiShotTransportStreamTest {
    @Test
    fun decodesShotsInArrivalOrder() =
        runTest {
            val engine =
                streamEngine {
                    sseShotChunk(EVENT_ID_1) + sseShotChunk(EVENT_ID_2) + sseShotChunk(EVENT_ID_3)
                }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.shots.test {
                transport.start()
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_1)
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_2)
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_3)
                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    @Test
    fun clubChangedEventUpdatesActiveClubWithoutEmittingAShot() =
        runTest {
            val engine = streamEngine { sseClubChangedChunk("7-iron") }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.activeClub.test {
                assertThat(awaitItem()).isNull()
                transport.start()
                assertThat(awaitItem()).isEqualTo(GolfClub.IRON_7)
                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    @Test
    fun automaticReconnectSuppressesTheReplayButAnExplicitRetryDoesNot() =
        runTest {
            // Every connection -- including a server-driven reconnect -- replays only the single
            // most recent shot (plan §0.2), so every mock response is identical.
            val engine = streamEngine { sseShotChunk(EVENT_ID_1) }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.shots.test {
                transport.start()
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_1)

                // The stream ends cleanly, so the transport reconnects automatically after the
                // initial 1 s backoff. lastEventId is preserved across that reconnect (§0.3), so
                // the replayed shot must not surface again.
                advanceTimeBy(1_100)
                runCurrent()
                expectNoEvents()

                // An explicit retry() resets the decoder, so the same replay is delivered again.
                transport.retry()
                runCurrent()
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_1)

                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    @Test
    fun aMalformedShotPayloadSetsStateToARetryableError() =
        runTest {
            val engine = streamEngine { "event: shot\ndata: not json\n\n" }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.state.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                transport.start()
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connecting)
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connected)
                val error = awaitItem()
                assertThat(error).isInstanceOf<ConnectionState.Error>()
                assertThat((error as ConnectionState.Error).canRetry).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    @Test
    fun tooManyDevicesRespondsWithARetryableError() =
        runTest {
            val engine = MockEngine { respond(content = "", status = HttpStatusCode.ServiceUnavailable) }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.state.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                transport.start()
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connecting)
                assertThat(awaitItem()).isEqualTo(
                    ConnectionState.Error(OpenFlightHttpError.UnexpectedStatus(503).message.orEmpty()),
                )
                cancelAndIgnoreRemainingEvents()
            }
            transport.disconnect()
        }

    @Test
    fun aBlankHostReportsAnActionableErrorWithoutConnecting() =
        runTest {
            var requested = false
            val engine =
                MockEngine {
                    requested = true
                    respond(content = "")
                }
            val transport = wifiShotTransport(engine, testScheduler, host = " ")

            transport.state.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                transport.start()
                assertThat(awaitItem()).isEqualTo(
                    ConnectionState.Error(OpenFlightHttpError.InvalidHost(" ").message.orEmpty()),
                )
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(requested).isFalse()
            transport.disconnect()
        }

    @Test
    fun disconnectStopsTheRunLoop() =
        runTest {
            val engine = streamEngine { sseShotChunk(EVENT_ID_1) }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.shots.test {
                transport.start()
                assertThat(awaitItem().eventId).isEqualTo(EVENT_ID_1)
                transport.disconnect()
                // The stream had already ended cleanly by this point, so without cancellation the
                // 1 s backoff would fire another reconnect (and another replay) here.
                advanceTimeBy(2_000)
                runCurrent()
                expectNoEvents()
            }
            assertThat(transport.state.value).isEqualTo(ConnectionState.Idle)
        }
}

private fun streamEngine(body: () -> String): MockEngine =
    MockEngine {
        respond(
            content = ByteReadChannel(body().encodeToByteArray()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }
