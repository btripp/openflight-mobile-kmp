// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.geodata.weatherClient
import dev.openflight.companion.core.location.LocationAccuracy
import dev.openflight.companion.core.location.LocationProvider
import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/** [DataStoreConditionsRepository] against a real DataStore file in the temp directory (plan F2, F6). */
class DataStoreConditionsRepositoryTest {
    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "openflight-conditions-${Random.nextLong().toULong()}"
    private val file: Path = directory / DataStoreSettingsRepository.FILE_NAME
    private val jobs = mutableListOf<Job>()

    @AfterTest
    fun tearDown() {
        jobs.forEach { it.cancel() }
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    private fun CoroutineScope.scope(): CoroutineScope = CoroutineScope(coroutineContext + Job().also { jobs += it })

    private fun CoroutineScope.repository(
        locationProvider: LocationProvider = FakeLocationProvider(LocationResult.PermissionDenied),
        weatherClient: dev.openflight.companion.core.geodata.WeatherClient = FakeWeatherClient(),
    ): DataStoreConditionsRepository {
        val scope = scope()
        return DataStoreConditionsRepository(
            createSettingsDataStore(path = file.toString(), scope = scope),
            scope,
            locationProvider,
            weatherClient,
        )
    }

    @Test
    fun emptyStoreIsIsaManualWithoutABearing() =
        runTest {
            val repository = backgroundScope.repository()

            assertThat(repository.conditions.first()).isEqualTo(Conditions.ISA)
            assertThat(repository.mode.value).isEqualTo(ConditionsMode.MANUAL)
            assertThat(repository.targetBearing.value).isNull()
            assertThat(repository.lastError.value).isNull()
            assertThat(repository.lastLocation.value).isNull()
        }

    @Test
    fun manualConditionsRoundTrip() =
        runTest {
            val repository = backgroundScope.repository()
            val denver =
                Conditions.ISA.copy(
                    altitudeMeters = 1609.0,
                    temperatureC = 28.0,
                    humidityPct = 35.0,
                    pressureHpa = 840.0,
                    wind = Wind(speedMps = 4.5, fromDegrees = 250.0),
                    surface = Firmness.FIRM,
                )

            repository.conditions.test {
                assertThat(awaitItem()).isEqualTo(Conditions.ISA)
                repository.setManual(denver)
                assertThat(awaitItem()).isEqualTo(denver)
            }
        }

    @Test
    fun clearingOptionalValuesRemovesThem() =
        runTest {
            val repository = backgroundScope.repository()
            repository.setManual(Conditions.ISA.copy(humidityPct = 50.0, pressureHpa = 1000.0))

            repository.setManual(Conditions.ISA)

            assertThat(repository.conditions.first { it == Conditions.ISA }).isEqualTo(Conditions.ISA)
        }

    @Test
    fun surfaceAndBearingPersistSeparately() =
        runTest {
            val repository = backgroundScope.repository()

            repository.setSurface(Firmness.SOFT)
            repository.setTargetBearing(TargetBearing(-90.0))

            assertThat(repository.conditions.first { it.surface == Firmness.SOFT }.altitudeMeters).isEqualTo(0.0)
            assertThat(repository.targetBearing.first { it != null }).isEqualTo(TargetBearing(270.0))

            repository.setTargetBearing(null)
            assertThat(repository.targetBearing.first { it == null }).isNull()
        }

    @Test
    fun valuesSurviveANewRepositoryOnTheSameStore() =
        runTest {
            val scope = backgroundScope.scope()
            val dataStore = createSettingsDataStore(path = file.toString(), scope = scope)
            val first =
                DataStoreConditionsRepository(
                    dataStore,
                    scope,
                    FakeLocationProvider(LocationResult.PermissionDenied),
                    FakeWeatherClient(),
                )
            first.setManual(Conditions.ISA.copy(altitudeMeters = 300.0))
            first.setTargetBearing(TargetBearing(45.0))

            val second =
                DataStoreConditionsRepository(
                    dataStore,
                    scope,
                    FakeLocationProvider(LocationResult.PermissionDenied),
                    FakeWeatherClient(),
                )

            assertThat(second.conditions.first { it.altitudeMeters == 300.0 }.altitudeMeters).isEqualTo(300.0)
            assertThat(second.targetBearing.first { it != null }).isEqualTo(TargetBearing(45.0))
        }

    @Test
    fun refreshIsANoOpInManual() =
        runTest {
            val repository = backgroundScope.repository()
            repository.setManual(Conditions.ISA.copy(temperatureC = 30.0))

            repository.refresh()

            assertThat(repository.conditions.first { it.temperatureC == 30.0 }.temperatureC).isEqualTo(30.0)
        }

    @Test
    fun setModeAutoWithASuccessfulFixAndWeatherStoresConditionsAndClearsTheError() =
        runTest {
            val fix = LocationResult.Fix(lat = 39.7392, lon = -104.9903, altitudeM = null, accuracyM = 50.0)
            val observation = weatherObservation(elevationMeters = 1609.0, tempC = 28.0)
            val repository = backgroundScope.repository(FakeLocationProvider(fix), FakeWeatherClient(observation))

            repository.setMode(ConditionsMode.AUTO)

            assertThat(repository.mode.value).isEqualTo(ConditionsMode.AUTO)
            assertThat(
                repository.conditions.first { it.source == ConditionsSource.WEATHER }.altitudeMeters,
            ).isEqualTo(1609.0)
            assertThat(repository.conditions.value.temperatureC).isEqualTo(28.0)
            assertThat(repository.lastLocation.value).isEqualTo(fix)
            assertThat(repository.lastError.value).isNull()
        }

    @Test
    fun deniedPermissionFallsBackToManualWithAMessage() =
        runTest {
            val repository = backgroundScope.repository(FakeLocationProvider(LocationResult.PermissionDenied))

            repository.setMode(ConditionsMode.AUTO)

            assertThat(repository.mode.value).isEqualTo(ConditionsMode.MANUAL)
            assertThat(repository.lastError.value).isEqualTo(ConditionsError.LocationPermissionDenied)
            assertThat(
                repository.lastError.value?.userMessage,
            ).isEqualTo(ConditionsError.LocationPermissionDenied.userMessage)
        }

    @Test
    fun disabledLocationServicesAlsoFallBackToManual() =
        runTest {
            val repository = backgroundScope.repository(FakeLocationProvider(LocationResult.Disabled))

            repository.setMode(ConditionsMode.AUTO)

            assertThat(repository.mode.value).isEqualTo(ConditionsMode.MANUAL)
            assertThat(repository.lastError.value).isEqualTo(ConditionsError.LocationDisabled)
        }

    @Test
    fun aWeatherFailureStaysInAutoAndKeepsTheLastConditions() =
        runTest {
            val fix = LocationResult.Fix(lat = 1.0, lon = 2.0, altitudeM = null, accuracyM = 10.0)
            val locationProvider = FakeLocationProvider(fix)
            val weather = FakeWeatherClient(failNext = true)
            val repository = backgroundScope.repository(locationProvider, weather)
            repository.setManual(Conditions.ISA.copy(temperatureC = 12.0))

            repository.setMode(ConditionsMode.AUTO)

            assertThat(repository.mode.value).isEqualTo(ConditionsMode.AUTO)
            assertThat(repository.lastError.value!!).isInstanceOf<ConditionsError.WeatherUnavailable>()
            // No successful AUTO fetch ever landed, so conditions falls back to the manual value.
            assertThat(repository.conditions.value.temperatureC).isEqualTo(12.0)
        }

    @Test
    fun switchingBackToManualRestoresTheManualEntryNotTheLastAutoFetch() =
        runTest {
            val fix = LocationResult.Fix(lat = 1.0, lon = 2.0, altitudeM = null, accuracyM = 10.0)
            val repository =
                backgroundScope.repository(FakeLocationProvider(fix), FakeWeatherClient(weatherObservation(500.0, 5.0)))
            repository.setManual(Conditions.ISA.copy(altitudeMeters = 42.0))
            repository.setMode(ConditionsMode.AUTO)
            assertThat(repository.conditions.first { it.altitudeMeters == 500.0 }.altitudeMeters).isEqualTo(500.0)

            repository.setMode(ConditionsMode.MANUAL)

            assertThat(repository.conditions.first { it.altitudeMeters == 42.0 }.altitudeMeters).isEqualTo(42.0)
        }

    @Test
    fun refreshInAutoCallsTheLocationProviderAgain() =
        runTest {
            val fix = LocationResult.Fix(lat = 1.0, lon = 2.0, altitudeM = null, accuracyM = 10.0)
            val locationProvider = FakeLocationProvider(fix)
            val repository =
                backgroundScope.repository(
                    locationProvider,
                    FakeWeatherClient(weatherObservation(10.0, 10.0)),
                )
            repository.setMode(ConditionsMode.AUTO)
            val callsAfterSetMode = locationProvider.callCount

            repository.refresh()

            assertThat(locationProvider.callCount).isEqualTo(callsAfterSetMode + 1)
        }

    @Test
    fun theRealWeatherClientsDiskCacheMeansASecondRefreshWithinTheTtlMakesNoNetworkCall() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests += 1
                    respond(
                        content = DENVER_FORECAST_JSON,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val testJson = Json { ignoreUnknownKeys = true }
            val httpClient =
                HttpClient(engine) {
                    install(ContentNegotiation) { json(testJson) }
                    install(HttpTimeout)
                }
            val cacheDir = directory / "geodata-cache"
            val realWeatherClient = weatherClient(httpClient, cacheDir)
            val fix = LocationResult.Fix(lat = 39.7392, lon = -104.9903, altitudeM = null, accuracyM = 30.0)
            val repository = backgroundScope.repository(FakeLocationProvider(fix), realWeatherClient)

            repository.setMode(ConditionsMode.AUTO)
            repository.refresh()

            assertThat(requests).isEqualTo(1)
            assertThat(repository.conditions.value.source == ConditionsSource.WEATHER).isTrue()
        }

    private fun weatherObservation(
        elevationMeters: Double,
        tempC: Double,
    ) = dev.openflight.companion.core.geodata.WeatherObservation(
        tempC = tempC,
        pressureHpa = 1013.0,
        humidityPct = 50.0,
        wind = Wind(speedMps = 2.0, fromDegrees = 180.0),
        elevationMeters = elevationMeters,
        observedAtEpochMillis = 0L,
    )

    private class FakeLocationProvider(
        private val result: LocationResult,
    ) : LocationProvider {
        var callCount = 0
            private set

        override suspend fun current(accuracy: LocationAccuracy): LocationResult {
            callCount += 1
            return result
        }
    }

    private class FakeWeatherClient(
        private val observation: dev.openflight.companion.core.geodata.WeatherObservation? = null,
        private val failNext: Boolean = false,
    ) : dev.openflight.companion.core.geodata.WeatherClient {
        override suspend fun current(
            lat: Double,
            lon: Double,
        ): dev.openflight.companion.core.geodata.WeatherResult =
            if (failNext || observation == null) {
                dev.openflight.companion.core.geodata.WeatherResult.Failure(
                    dev.openflight.companion.core.geodata.WeatherFailure
                        .Network("offline"),
                )
            } else {
                dev.openflight.companion.core.geodata.WeatherResult
                    .Success(observation)
            }
    }

    private companion object {
        const val DENVER_FORECAST_JSON =
            """{"latitude":39.7,"longitude":-104.9,"elevation":1609.0,"current":{"time":"2026-09-25T23:00",""" +
                """"temperature_2m":16.7,"relative_humidity_2m":72,"surface_pressure":837.6,""" +
                """"wind_speed_10m":3.74,"wind_direction_10m":164}}"""
    }
}
