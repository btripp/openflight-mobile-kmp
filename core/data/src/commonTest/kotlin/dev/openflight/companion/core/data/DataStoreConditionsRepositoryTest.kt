// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/** The MANUAL conditions repository against a real DataStore file in the temp directory. */
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

    private fun CoroutineScope.repository(): DataStoreConditionsRepository {
        val scope = scope()
        return DataStoreConditionsRepository(createSettingsDataStore(path = file.toString(), scope = scope), scope)
    }

    @Test
    fun emptyStoreIsIsaManualWithoutABearing() =
        runTest {
            val repository = backgroundScope.repository()

            assertThat(repository.conditions.first()).isEqualTo(Conditions.ISA)
            assertThat(repository.mode.value).isEqualTo(ConditionsMode.MANUAL)
            assertThat(repository.targetBearing.value).isNull()
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
            val first = DataStoreConditionsRepository(dataStore, scope)
            first.setManual(Conditions.ISA.copy(altitudeMeters = 300.0))
            first.setTargetBearing(TargetBearing(45.0))

            val second = DataStoreConditionsRepository(dataStore, scope)

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
}
