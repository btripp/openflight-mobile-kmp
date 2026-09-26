// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import dev.openflight.companion.core.model.Wind
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The Open-Meteo forecast API's [WeatherClient] (plan F6 task 2), built on [httpClient]. A
 * successful read is cached in [cache] (plan: 15-minute TTL) and served from there on the next
 * call for a nearby coordinate, so `ConditionsRepository.refresh()` never has to guess whether a
 * request is "too soon".
 */
internal class OpenMeteoWeatherClient(
    private val httpClient: HttpClient,
    private val cache: GeodataCache,
) : WeatherClient {
    @Suppress("ReturnCount") // One early exit per outcome (cache hit, network failure) reads clearer than nesting.
    @OptIn(ExperimentalTime::class)
    override suspend fun current(
        lat: Double,
        lon: Double,
    ): WeatherResult {
        val key = cacheKey(lat, lon)
        cache.read(key)?.let { cached -> parse(cached)?.let { return WeatherResult.Success(it) } }

        val response =
            runCatching {
                httpClient.get(FORECAST_URL) {
                    parameter("latitude", lat)
                    parameter("longitude", lon)
                    parameter("current", CURRENT_FIELDS)
                    parameter("wind_speed_unit", "ms")
                    parameter("timezone", "UTC")
                    timeout { requestTimeoutMillis = TIMEOUT_MILLIS }
                }
            }.getOrElse { error ->
                val failure =
                    if (error is HttpRequestTimeoutException) {
                        WeatherFailure.Timeout
                    } else {
                        WeatherFailure.Network(error.message ?: "Unknown network error")
                    }
                return WeatherResult.Failure(failure)
            }

        return handle(response, key)
    }

    @Suppress("ReturnCount") // One early exit per HTTP outcome reads clearer than nesting.
    private suspend fun handle(
        response: HttpResponse,
        key: String,
    ): WeatherResult {
        if (response.status == HttpStatusCode.TooManyRequests) {
            return WeatherResult.Failure(WeatherFailure.RateLimited)
        }
        if (!response.status.isSuccess()) {
            return WeatherResult.Failure(WeatherFailure.UnexpectedStatus(response.status.value))
        }
        val body =
            runCatching { response.bodyAsText() }
                .getOrElse {
                    return WeatherResult.Failure(
                        WeatherFailure.MalformedResponse(it.message ?: "Empty body"),
                    )
                }
        val observation =
            parse(body) ?: return WeatherResult.Failure(WeatherFailure.MalformedResponse("Unexpected response shape"))
        cache.write(key, body)
        return WeatherResult.Success(observation)
    }

    @Suppress("ReturnCount") // One early exit per parse failure reads clearer than nesting.
    @OptIn(ExperimentalTime::class)
    private fun parse(body: String): WeatherObservation? {
        val response =
            runCatching { GeodataJson.decodeFromString(OpenMeteoResponse.serializer(), body) }.getOrNull()
                ?: return null
        val current = response.current
        val observedAt = runCatching { Instant.parse("${current.time}:00Z") }.getOrNull() ?: return null
        return WeatherObservation(
            tempC = current.temperatureC,
            pressureHpa = current.pressureHpa,
            humidityPct = current.humidityPct,
            wind = Wind(speedMps = current.windSpeedMps, fromDegrees = current.windDirectionDeg),
            elevationMeters = response.elevation,
            observedAtEpochMillis = observedAt.toEpochMilliseconds(),
        )
    }

    /** ~1.1 km buckets, so GPS jitter between two calls at the same spot still hits [cache]. */
    private fun cacheKey(
        lat: Double,
        lon: Double,
    ): String {
        fun round2(value: Double) = round(value * CACHE_KEY_PRECISION) / CACHE_KEY_PRECISION
        return "lat${round2(lat)}_lon${round2(lon)}"
    }

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,wind_direction_10m"
        const val TIMEOUT_MILLIS = 10_000L
        const val CACHE_KEY_PRECISION = 100.0
    }
}

@Serializable
internal data class OpenMeteoResponse(
    val elevation: Double,
    val current: OpenMeteoCurrent,
)

@Serializable
internal data class OpenMeteoCurrent(
    val time: String,
    @SerialName("temperature_2m") val temperatureC: Double,
    @SerialName("relative_humidity_2m") val humidityPct: Double,
    @SerialName("surface_pressure") val pressureHpa: Double,
    @SerialName("wind_speed_10m") val windSpeedMps: Double,
    @SerialName("wind_direction_10m") val windDirectionDeg: Double,
)
