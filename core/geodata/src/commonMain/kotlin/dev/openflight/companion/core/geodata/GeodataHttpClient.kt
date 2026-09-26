// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * `OpenFlightCompanion/<version>`, sent as `User-Agent` on every request this module makes
 * (plan F6 task 2; Open-Meteo asks callers to identify themselves). Tracks `androidApp`'s
 * `versionName`; there's no `BuildConfig` plumbing into a KMP `core` module for it, so it's a
 * plain literal, bumped alongside a release.
 */
internal const val GEODATA_USER_AGENT = "OpenFlightCompanion/0.1.0"

/** Tolerant of fields this client doesn't read yet (plan A20's `ignoreUnknownKeys` habit, reused here). */
internal val GeodataJson: Json =
    Json {
        ignoreUnknownKeys = true
    }

/**
 * Installs the plugins every geodata [HttpClient] needs: JSON content negotiation, the
 * `User-Agent`, and [HttpTimeout] so [OpenMeteoWeatherClient] can set the request's 10 s ceiling
 * per plan F6 task 2.
 */
internal fun HttpClientConfig<*>.installGeodataDefaults() {
    install(ContentNegotiation) { json(GeodataJson) }
    install(HttpTimeout)
    defaultRequest { header(HttpHeaders.UserAgent, GEODATA_USER_AGENT) }
}

/**
 * Builds the single geodata [HttpClient]: OkHttp on Android, Darwin on iOS, https only (this
 * module never accepts a user-supplied host, unlike the Pi's `EndpointPolicy`-gated one in
 * `core:network`).
 */
expect fun geodataHttpClient(): HttpClient
