// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import dev.openflight.companion.core.protocol.OpenFlightJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Installs the plugins every OpenFlight [HttpClient] needs, regardless of engine: JSON content
 * negotiation using the shared [OpenFlightJson] instance (so `encodeDefaults`/`ignoreUnknownKeys`
 * match every other codec), and [HttpTimeout] so [WifiShotTransport] can set the stream's infinite
 * request timeout and the control calls' 10 s timeout per-request.
 */
internal fun HttpClientConfig<*>.installOpenFlightDefaults() {
    install(ContentNegotiation) {
        json(OpenFlightJson)
    }
    install(HttpTimeout)
}

/**
 * Builds the single [HttpClient] `core:data` (step 6) provides through Koin: the OkHttp engine on
 * Android, the Darwin engine on iOS. One instance is shared by every [WifiShotTransport], since
 * each instance only carries a `host` and is otherwise stateless engine-wise.
 */
expect fun openFlightHttpClient(): HttpClient
