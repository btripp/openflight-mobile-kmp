// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import dev.openflight.companion.core.model.Wind
import io.ktor.client.HttpClient
import okio.FileSystem
import okio.Path
import kotlin.time.Duration.Companion.minutes

/**
 * One Open-Meteo forecast response's `current` block (plan §0.3): temperature, station pressure,
 * humidity, wind and elevation, all in one call, so there is **no separate elevation client**.
 *
 * @property elevationMeters the model grid cell's elevation, from the response's top-level
 *   `elevation` field (not part of `current`).
 * @property observedAtEpochMillis parsed from `current.time` (ISO-8601, the location's local time
 *   per the API's `timezone=auto`... this client requests UTC explicitly, so it's UTC).
 */
data class WeatherObservation(
    val tempC: Double,
    val pressureHpa: Double,
    val humidityPct: Double,
    val wind: Wind,
    val elevationMeters: Double,
    val observedAtEpochMillis: Long,
)

/** Why [WeatherClient.current] couldn't produce a [WeatherObservation]. */
sealed interface WeatherFailure {
    /** No response reached the client (offline, DNS, connection refused, ...). */
    data class Network(
        val message: String,
    ) : WeatherFailure

    /** The request timed out (this client's 10 s Ktor timeout, plan F6 task 2). */
    data object Timeout : WeatherFailure

    /** Open-Meteo answered `429 Too Many Requests`. */
    data object RateLimited : WeatherFailure

    /** A non-200, non-429 HTTP status. */
    data class UnexpectedStatus(
        val statusCode: Int,
    ) : WeatherFailure

    /** A 200 response whose body isn't the JSON shape this client expects. */
    data class MalformedResponse(
        val message: String,
    ) : WeatherFailure
}

/** [WeatherClient.current]'s result: a reading, or a typed reason it couldn't be fetched. */
sealed interface WeatherResult {
    data class Success(
        val observation: WeatherObservation,
    ) : WeatherResult

    data class Failure(
        val reason: WeatherFailure,
    ) : WeatherResult
}

/**
 * Current weather + elevation for a coordinate, from the Open-Meteo forecast API (plan §0.3: CC
 * BY 4.0, no API key, non-commercial free tier -- this AGPL hobby app qualifies). Implementations
 * cache successful reads for 15 minutes (plan F6 task 2), so calling this more often than that
 * from the same coordinate doesn't reach the network.
 */
interface WeatherClient {
    suspend fun current(
        lat: Double,
        lon: Double,
    ): WeatherResult
}

/**
 * Builds the Open-Meteo [WeatherClient], sharing [httpClient]'s engine and caching successful
 * reads under [cacheDirectory] with the plan's 15-minute TTL. `core:data`'s platform Koin module
 * resolves [cacheDirectory] the same way it resolves the settings `DataStore`'s file path (plan
 * F6, A1: `core:geodata` stays outside the Pi's LAN-only `EndpointPolicy`).
 */
fun weatherClient(
    httpClient: HttpClient,
    cacheDirectory: Path,
): WeatherClient =
    OpenMeteoWeatherClient(
        httpClient = httpClient,
        cache = GeodataCache(FileSystem.SYSTEM, cacheDirectory, ttl = WEATHER_CACHE_TTL),
    )

private val WEATHER_CACHE_TTL = 15.minutes
