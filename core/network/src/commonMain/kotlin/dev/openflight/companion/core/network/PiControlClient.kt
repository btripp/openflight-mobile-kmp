// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** `POST /api/shutdown`'s body (plan R5a, `src/openflight/server.py:1167`: `{"status":"shutting_down"}`). */
@Serializable
data class PiShutdownResult(
    val status: String,
)

/**
 * Wi-Fi-only Pi control calls that aren't part of [dev.openflight.companion.core.protocol.ShotTransport]
 * (plan R5a): today, just asking the Pi to shut itself down. Ported from `src/openflight/server.py`'s
 * `api_shutdown`, which needs no request body and always answers `200 {"status":"shutting_down"}` on
 * success.
 */
class PiControlClient(
    private val httpClient: HttpClient,
) {
    /** @throws OpenFlightHttpError.InvalidHost or [OpenFlightHttpError.UnexpectedStatus] on failure. */
    suspend fun shutdown(host: String): PiShutdownResult {
        val url = EndpointUrl.require(host, SHUTDOWN_PATH)
        val response =
            httpClient.request(url) {
                method = HttpMethod.Post
                timeout { requestTimeoutMillis = CONTROL_TIMEOUT_MILLIS }
            }
        if (response.status != HttpStatusCode.OK) {
            throw OpenFlightHttpError.UnexpectedStatus(response.status.value, serverErrorMessage(response))
        }
        return response.body()
    }

    companion object {
        const val SHUTDOWN_PATH = "/api/shutdown"
        private const val CONTROL_TIMEOUT_MILLIS = 10_000L
    }
}

@Serializable
private data class PiControlErrorBody(
    val error: String? = null,
)

private suspend fun serverErrorMessage(response: HttpResponse): String? =
    runCatching { response.body<PiControlErrorBody>().error }.getOrNull()
