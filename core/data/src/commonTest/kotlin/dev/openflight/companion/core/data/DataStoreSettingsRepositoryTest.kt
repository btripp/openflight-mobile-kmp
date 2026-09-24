// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/** The real DataStore-backed settings against a file in the system temp directory. */
class DataStoreSettingsRepositoryTest {
    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "openflight-settings-${Random.nextLong().toULong()}"
    private val file: Path = directory / DataStoreSettingsRepository.FILE_NAME
    private val jobs = mutableListOf<Job>()

    @AfterTest
    fun tearDown() {
        jobs.forEach { it.cancel() }
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    private fun CoroutineScope.dataStore() =
        Job().also { jobs += it }.let { job ->
            createSettingsDataStore(path = file.toString(), scope = CoroutineScope(coroutineContext + job))
        }

    @Test
    fun emptyStoreReadsTheReferenceDefaults() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            assertThat(settings.transport.first()).isEqualTo(TransportType.BLUETOOTH)
            assertThat(settings.host.first()).isEqualTo("raspberrypi.local:8080")
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.DRIVER)
        }

    @Test
    fun writtenValuesRoundTrip() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            settings.setTransport(TransportType.WIFI)
            settings.setHost("192.168.1.20:8080")
            settings.setSelectedClub(GolfClub.PITCHING_WEDGE)

            assertThat(settings.transport.first()).isEqualTo(TransportType.WIFI)
            assertThat(settings.host.first()).isEqualTo("192.168.1.20:8080")
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.PITCHING_WEDGE)
        }

    @Test
    fun valuesAreStoredAsTheReferenceRawValues() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            val settings = DataStoreSettingsRepository(dataStore)

            settings.setTransport(TransportType.WIFI)
            settings.setSelectedClub(GolfClub.IRON_7)

            val stored = dataStore.data.first()
            assertThat(stored[stringPreferencesKey("shotTransport")]).isEqualTo("wifi")
            assertThat(stored[stringPreferencesKey("selectedClub")]).isEqualTo("7-iron")
        }

    @Test
    fun unrecognizedStoredValuesFallBackToDefaults() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            dataStore.edit {
                it[stringPreferencesKey("shotTransport")] = "carrier-pigeon"
                it[stringPreferencesKey("selectedClub")] = "putter"
            }
            val settings = DataStoreSettingsRepository(dataStore)

            assertThat(settings.transport.first()).isEqualTo(TransportType.BLUETOOTH)
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.DRIVER)
        }
}
