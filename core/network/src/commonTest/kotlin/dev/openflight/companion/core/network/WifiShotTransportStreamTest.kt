// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
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
    fun aStockPiWithoutTheStreamIsProbedOnceUntilAnExplicitRetry() =
        runTest {
            // Plan R8j: stock upstream has no /api/shots/stream, so Flask answers 404.
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    respond(content = "Not Found", status = HttpStatusCode.NotFound)
                }
            val transport = wifiShotTransport(engine, testScheduler)

            transport.state.test {
                assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
                transport.start()
                assertThat(awaitItem()).isEqualTo(ConnectionState.Connecting)
                val error = awaitItem() as ConnectionState.Error
                assertThat(error.kind).isEqualTo(ConnectionErrorKind.STREAM_UNAVAILABLE)
                assertThat(error.description.contains("404")).isTrue()

                // Well past the 15 s maximum backoff: nothing is retried on its own.
                advanceTimeBy(60_000)
                runCurrent()
                expectNoEvents()
                assertThat(requests).isEqualTo(1)

                // An explicit retry probes again.
                transport.retry()
                runCurrent()
                // Idle and Connecting may be conflated; the probe ends in the same error.
                var next = awaitItem()
                while (next !is ConnectionState.Error) next = awaitItem()
                assertThat(next.kind).isEqualTo(ConnectionErrorKind.STREAM_UNAVAILABLE)
                assertThat(requests).isEqualTo(2)
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
                    ConnectionState.Error(EndpointDecision.Blank.reason, ConnectionErrorKind.ENDPOINT_REJECTED),
                )
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(requested).isFalse()
            transport.disconnect()
        }

    /**
     * Issue #70: OkHttp throws `IllegalArgumentException` for a host it can't put in a URL. Retrying
     * can't fix the address, so the transport stops with a plain-words error until [retry].
     */
    @Test
    fun anAddressTheHttpEngineRefusesStopsRetryingWithAPlainWordsError() =
        runTest {
            var requests = 0
            val engine =
                virtualTimeEngine(testScheduler) {
                    requests++
                    throw IllegalArgumentException("Invalid URL host: \"[fe80::1%en0]\"")
                }
            val transport = wifiShotTransport(engine, testScheduler)

            try {
                transport.start()
                runCurrent()
                assertThat(transport.state.value).isEqualTo(
                    ConnectionState.Error(
                        EndpointDecision.Malformed(DEFAULT_TEST_HOST).reason,
                        ConnectionErrorKind.ENDPOINT_REJECTED,
                    ),
                )

                // Well past the 15 s maximum backoff: nothing is retried on its own.
                advanceTimeBy(60_000)
                runCurrent()
                assertThat(requests).isEqualTo(1)

                transport.retry()
                runCurrent()
                assertThat(requests).isEqualTo(2)
            } finally {
                // A still-retrying loop would keep the virtual clock busy forever after a failure.
                transport.disconnect()
            }
        }

    /**
     * Issue #69: real network drops end the body with an error, never a clean end of stream. A
     * connection that got its `200` counts as working, so the next reconnect waits the initial 1 s
     * again. Before the fix the gaps grew 1, 2, 4, 8 s, then stayed at 15 s for good.
     */
    @Test
    fun aStreamThatConnectedAndThenDroppedReconnectsAfterTheInitialDelay() =
        runTest {
            val requestTimes = mutableListOf<Long>()
            val engine =
                virtualTimeEngine(testScheduler) {
                    requestTimes += testScheduler.currentTime
                    val body = ByteChannel(autoFlush = true)
                    body.writeFully(sseShotChunk(EVENT_ID_1).encodeToByteArray())
                    body.cancel(IOException("Connection reset"))
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                }
            val transport = wifiShotTransport(engine, testScheduler)

            try {
                transport.start()
                advanceTimeBy(4_500)
                runCurrent()

                assertThat(requestTimes).containsExactly(0L, 1_000L, 2_000L, 3_000L, 4_000L)
            } finally {
                transport.disconnect()
            }
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

/** A [MockEngine] whose requests run on the test's virtual clock, so `advanceTimeBy` covers them. */
private fun virtualTimeEngine(
    scheduler: TestCoroutineScheduler,
    handler: MockRequestHandler,
): MockEngine =
    MockEngine(
        MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scheduler)
            addHandler(handler)
        },
    )
