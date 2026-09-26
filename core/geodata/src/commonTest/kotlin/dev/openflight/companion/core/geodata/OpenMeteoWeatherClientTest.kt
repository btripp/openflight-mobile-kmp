// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * [OpenMeteoWeatherClient] against [WeatherFixtures] (plan F6). Each test builds its own
 * `MockEngine` client and a [GeodataCache] rooted in a fresh temp directory.
 */
class OpenMeteoWeatherClientTest {
    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "openflight-geodata-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    private fun cache(): GeodataCache = GeodataCache(FileSystem.SYSTEM, directory, ttl = 15.minutes)

    private fun clientWith(engine: MockEngine): OpenMeteoWeatherClient =
        OpenMeteoWeatherClient(
            httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(GeodataJson) }
                    install(HttpTimeout)
                },
            cache = cache(),
        )

    private fun MockRequestHandleScope.jsonResponse(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun aSuccessfulResponseParsesIntoAWeatherObservation() =
        runTest {
            val engine = MockEngine { jsonResponse(WeatherFixtures.DENVER_RESPONSE) }
            val client = clientWith(engine)

            val result = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(result).isInstanceOf<WeatherResult.Success>()
            val observation = (result as WeatherResult.Success).observation
            assertThat(observation.elevationMeters).isEqualTo(1599.0)
            assertThat(observation.tempC).isEqualTo(16.7)
            assertThat(observation.pressureHpa).isEqualTo(837.6)
            assertThat(observation.humidityPct).isEqualTo(72.0)
            assertThat(observation.wind.speedMps).isEqualTo(3.74)
            assertThat(observation.wind.fromDegrees).isEqualTo(164.0)
        }

    @Test
    fun aSecondCallCoordinateHitsTheCacheInsteadOfTheNetwork() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests += 1
                    jsonResponse(WeatherFixtures.ST_ANDREWS_RESPONSE)
                }
            val client = clientWith(engine)

            client.current(WeatherFixtures.ST_ANDREWS_LAT, WeatherFixtures.ST_ANDREWS_LON)
            val second = client.current(WeatherFixtures.ST_ANDREWS_LAT, WeatherFixtures.ST_ANDREWS_LON)

            assertThat(requests).isEqualTo(1)
            assertThat(second).isInstanceOf<WeatherResult.Success>()
        }

    @Test
    fun aTwoDifferentClientInstancesShareTheDiskCache() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests += 1
                    jsonResponse(WeatherFixtures.DENVER_RESPONSE)
                }
            val sharedCache = cache()
            val first =
                OpenMeteoWeatherClient(
                    HttpClient(engine) { install(ContentNegotiation) { json(GeodataJson) } },
                    sharedCache,
                )
            val second =
                OpenMeteoWeatherClient(
                    HttpClient(engine) { install(ContentNegotiation) { json(GeodataJson) } },
                    sharedCache,
                )

            first.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)
            second.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(requests).isEqualTo(1)
        }

    @Test
    fun aMalformedBodyIsReportedNotThrown() =
        runTest {
            val engine = MockEngine { jsonResponse(WeatherFixtures.MALFORMED_RESPONSE) }
            val client = clientWith(engine)

            val result = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(result).isInstanceOf<WeatherResult.Failure>()
            assertThat((result as WeatherResult.Failure).reason).isInstanceOf<WeatherFailure.MalformedResponse>()
        }

    @Test
    fun aWellFormedButUnexpectedShapeIsAlsoAMalformedResponse() =
        runTest {
            val engine = MockEngine { jsonResponse(WeatherFixtures.WRONG_SHAPE_RESPONSE) }
            val client = clientWith(engine)

            val result = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(result).isInstanceOf<WeatherResult.Failure>()
            assertThat((result as WeatherResult.Failure).reason).isInstanceOf<WeatherFailure.MalformedResponse>()
        }

    @Test
    fun aFourTwoNineIsReportedAsRateLimited() =
        runTest {
            val engine =
                MockEngine { jsonResponse(WeatherFixtures.INVALID_REQUEST_RESPONSE, HttpStatusCode.TooManyRequests) }
            val client = clientWith(engine)

            val result = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(result).isEqualTo(WeatherResult.Failure(WeatherFailure.RateLimited))
        }

    @Test
    fun aFourHundredIsAnUnexpectedStatusNotACrash() =
        runTest {
            val engine =
                MockEngine { jsonResponse(WeatherFixtures.INVALID_REQUEST_RESPONSE, HttpStatusCode.BadRequest) }
            val client = clientWith(engine)

            val result = client.current(999.0, WeatherFixtures.DENVER_LON)

            assertThat(result).isEqualTo(WeatherResult.Failure(WeatherFailure.UnexpectedStatus(400)))
        }

    @Test
    fun aRequestThatNeverAnswersTimesOut() =
        runTest {
            val engine =
                MockEngine {
                    delay(TEST_TIMEOUT_OVERSHOOT_MILLIS)
                    jsonResponse(WeatherFixtures.DENVER_RESPONSE)
                }
            val client = clientWith(engine)

            val result = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(result).isEqualTo(WeatherResult.Failure(WeatherFailure.Timeout))
        }

    @Test
    fun aFailedCallNeverPoisonsTheCache() =
        runTest {
            var attempt = 0
            val engine =
                MockEngine {
                    attempt += 1
                    if (attempt ==
                        1
                    ) {
                        jsonResponse(WeatherFixtures.MALFORMED_RESPONSE)
                    } else {
                        jsonResponse(WeatherFixtures.DENVER_RESPONSE)
                    }
                }
            val client = clientWith(engine)

            client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)
            val second = client.current(WeatherFixtures.DENVER_LAT, WeatherFixtures.DENVER_LON)

            assertThat(attempt).isEqualTo(2)
            assertThat(second is WeatherResult.Success).isTrue()
        }

    private companion object {
        const val TEST_TIMEOUT_OVERSHOOT_MILLIS = 20_000L
    }
}
