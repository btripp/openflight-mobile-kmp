// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** `PiControlClient.shutdown`, ported against `src/openflight/server.py`'s `POST /api/shutdown` (plan R5a). */
class PiControlClientTest {
    @Test
    fun shutdownPostsAndDecodesTheStatus() =
        runTest {
            var capturedMethod: HttpMethod? = null
            var capturedPath: String? = null
            val engine =
                MockEngine { request ->
                    capturedMethod = request.method
                    capturedPath = request.url.encodedPath
                    respond(
                        content = """{"status":"shutting_down"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = piControlClient(engine)

            val result = client.shutdown(DEFAULT_TEST_HOST)

            assertThat(result).isEqualTo(PiShutdownResult(status = "shutting_down"))
            assertThat(capturedMethod).isEqualTo(HttpMethod.Post)
            assertThat(capturedPath).isEqualTo(PiControlClient.SHUTDOWN_PATH)
        }

    @Test
    fun aNonTwoHundredResponseThrowsWithTheServersErrorMessage() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":"Pi is busy"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = piControlClient(engine)

            val error =
                try {
                    client.shutdown(DEFAULT_TEST_HOST)
                    null
                } catch (e: OpenFlightHttpError.UnexpectedStatus) {
                    e
                }

            assertThat(error?.statusCode).isEqualTo(HttpStatusCode.InternalServerError.value)
            assertThat(error?.serverMessage).isEqualTo("Pi is busy")
        }

    @Test
    fun aTypedAddressWithATrailingSlashPostsToTheSameEndpoint() =
        runTest {
            // Expo shutdown.test.ts: the field accepts whatever was typed, trailing slash included.
            val urls = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    urls += request.url.toString()
                    respond(
                        content = """{"status":"shutting_down"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = piControlClient(engine)

            client.shutdown("http://192.168.1.100:8080/")
            client.shutdown("192.168.1.100:8080")

            assertThat(urls.toList()).isEqualTo(List(2) { "http://192.168.1.100:8080/api/shutdown" })
        }

    @Test
    fun aRequestThatNeverReachedTheServerIsReportedNotSwallowed() =
        runTest {
            // Telling someone the Pi stopped when it didn't invites them to pull the plug.
            val engine = MockEngine { throw IllegalStateException("Network request failed") }
            val client = piControlClient(engine)

            val error = runCatching { client.shutdown(DEFAULT_TEST_HOST) }.exceptionOrNull()

            assertThat(error?.message).isEqualTo("Network request failed")
        }

    @Test
    fun anInvalidHostThrowsWithoutMakingARequest() =
        runTest {
            var requested = false
            val engine =
                MockEngine {
                    requested = true
                    respond(content = "")
                }
            val client = piControlClient(engine)

            val error =
                try {
                    client.shutdown(" ")
                    null
                } catch (e: OpenFlightHttpError.InvalidHost) {
                    e
                }

            assertThat(error?.host).isEqualTo(" ")
            assertThat(requested).isFalse()
        }
}

private fun piControlClient(engine: MockEngine): PiControlClient {
    val client = HttpClient(engine) { installOpenFlightDefaults() }
    return PiControlClient(client)
}
