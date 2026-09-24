// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * OkHttp streams response bodies incrementally by default (it never buffers a chunked body before
 * handing it to the caller), so no extra engine configuration is needed for the shot SSE stream
 * to deliver bytes as they arrive.
 */
actual fun openFlightHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        installOpenFlightDefaults()
    }
