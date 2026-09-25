// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem
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
            assertThat(settings.units.first()).isEqualTo(UnitSystem.IMPERIAL)
            assertThat(settings.rangeCameraMode.first()).isEqualTo(RangeCameraMode.FOLLOW)
        }

    @Test
    fun rangeCameraModeRoundTripsBothWays() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            settings.setRangeCameraMode(RangeCameraMode.FIXED)
            assertThat(settings.rangeCameraMode.first()).isEqualTo(RangeCameraMode.FIXED)

            settings.setRangeCameraMode(RangeCameraMode.FOLLOW)
            assertThat(settings.rangeCameraMode.first()).isEqualTo(RangeCameraMode.FOLLOW)
        }

    @Test
    fun rangeCameraModeSurvivesANewRepositoryOnTheSameStore() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            DataStoreSettingsRepository(dataStore).setRangeCameraMode(RangeCameraMode.FIXED)

            val reopened = DataStoreSettingsRepository(dataStore)

            assertThat(reopened.rangeCameraMode.first()).isEqualTo(RangeCameraMode.FIXED)
            assertThat(dataStore.data.first()[stringPreferencesKey("rangeCameraMode")]).isEqualTo("fixed")
        }

    @Test
    fun writtenValuesRoundTrip() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            settings.setTransport(TransportType.WIFI)
            settings.setHost("192.168.1.20:8080")
            settings.setSelectedClub(GolfClub.PITCHING_WEDGE)
            settings.setUnits(UnitSystem.METRIC)

            assertThat(settings.transport.first()).isEqualTo(TransportType.WIFI)
            assertThat(settings.host.first()).isEqualTo("192.168.1.20:8080")
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.PITCHING_WEDGE)
            assertThat(settings.units.first()).isEqualTo(UnitSystem.METRIC)
        }

    @Test
    fun valuesAreStoredAsTheReferenceRawValues() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            val settings = DataStoreSettingsRepository(dataStore)

            settings.setTransport(TransportType.WIFI)
            settings.setSelectedClub(GolfClub.IRON_7)
            settings.setUnits(UnitSystem.METRIC)

            val stored = dataStore.data.first()
            assertThat(stored[stringPreferencesKey("shotTransport")]).isEqualTo("wifi")
            assertThat(stored[stringPreferencesKey("selectedClub")]).isEqualTo("7-iron")
            assertThat(stored[stringPreferencesKey("unitSystem")]).isEqualTo("metric")
        }

    @Test
    fun aSubmittedHostIsUsedThisLaunchButNotPersisted() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            val settings = DataStoreSettingsRepository(dataStore)

            settings.setHost("192.168.4.1:8080")

            assertThat(settings.host.first()).isEqualTo("192.168.4.1:8080")
            assertThat(dataStore.data.first()[stringPreferencesKey("piHost")]).isNull()
            // The next launch (a fresh repository over the same file) falls back to the default.
            assertThat(DataStoreSettingsRepository(dataStore).host.first()).isEqualTo(SettingsRepository.DEFAULT_HOST)
        }

    @Test
    fun onlyAConnectedHostSurvivesARelaunch() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            val settings = DataStoreSettingsRepository(dataStore)
            settings.setHost("192.168.1.100:8080")
            settings.rememberConnectedHost("192.168.1.100:8080")
            settings.setHost("typo.local:8080") // Submitted, never connects.

            assertThat(settings.host.first()).isEqualTo("typo.local:8080")
            assertThat(DataStoreSettingsRepository(dataStore).host.first()).isEqualTo("192.168.1.100:8080")
            assertThat(dataStore.data.first()[stringPreferencesKey("piHost")]).isEqualTo("192.168.1.100:8080")
        }

    @Test
    fun theSubmittedHostKeepsWinningOverALaterRememberedOne() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())
            settings.host.test {
                assertThat(awaitItem()).isEqualTo(SettingsRepository.DEFAULT_HOST)
                settings.setHost("192.168.4.1:8080")
                assertThat(awaitItem()).isEqualTo("192.168.4.1:8080")
                settings.rememberConnectedHost("192.168.4.1:8080")
                expectNoEvents()
            }
        }

    @Test
    fun unrecognizedStoredValuesFallBackToDefaults() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            dataStore.edit {
                it[stringPreferencesKey("shotTransport")] = "carrier-pigeon"
                it[stringPreferencesKey("selectedClub")] = "putter"
                it[stringPreferencesKey("unitSystem")] = "furlongs-per-fortnight"
                it[stringPreferencesKey("rangeCameraMode")] = "drone"
            }
            val settings = DataStoreSettingsRepository(dataStore)

            assertThat(settings.transport.first()).isEqualTo(TransportType.BLUETOOTH)
            assertThat(settings.selectedClub.first()).isEqualTo(GolfClub.DRIVER)
            assertThat(settings.units.first()).isEqualTo(UnitSystem.IMPERIAL)
            assertThat(settings.rangeCameraMode.first()).isEqualTo(RangeCameraMode.FOLLOW)
        }

    // Plan F4: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    @Test
    fun emptyStoreReadsTheCalloutDefaults() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            assertThat(settings.calloutsEnabled.first()).isEqualTo(false)
            assertThat(settings.calloutVoiceId.first()).isNull()
            assertThat(settings.calloutRate.first()).isEqualTo(1f)
            assertThat(settings.calloutFields.first()).isEqualTo(listOf(CalloutField.CARRY, CalloutField.BALL_SPEED))
            assertThat(settings.calloutTrigger.first()).isEqualTo(CalloutTrigger.EVERY_SHOT)
        }

    @Test
    fun calloutSettingsRoundTrip() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())

            settings.setCalloutsEnabled(true)
            settings.setCalloutVoiceId("en-us-x-premium")
            settings.setCalloutRate(1.5f)
            settings.setCalloutFields(listOf(CalloutField.TOTAL, CalloutField.SMASH, CalloutField.CLUB))
            settings.setCalloutTrigger(CalloutTrigger.GAMES_ONLY)

            assertThat(settings.calloutsEnabled.first()).isEqualTo(true)
            assertThat(settings.calloutVoiceId.first()).isEqualTo("en-us-x-premium")
            assertThat(settings.calloutRate.first()).isEqualTo(1.5f)
            assertThat(
                settings.calloutFields.first(),
            ).isEqualTo(listOf(CalloutField.TOTAL, CalloutField.SMASH, CalloutField.CLUB))
            assertThat(settings.calloutTrigger.first()).isEqualTo(CalloutTrigger.GAMES_ONLY)
        }

    @Test
    fun calloutSettingsSurviveANewRepositoryOnTheSameStore() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            DataStoreSettingsRepository(dataStore).apply {
                setCalloutsEnabled(true)
                setCalloutFields(listOf(CalloutField.LAUNCH))
                setCalloutTrigger(CalloutTrigger.GAMES_ONLY)
            }

            val reopened = DataStoreSettingsRepository(dataStore)

            assertThat(reopened.calloutsEnabled.first()).isEqualTo(true)
            assertThat(reopened.calloutFields.first()).isEqualTo(listOf(CalloutField.LAUNCH))
            assertThat(reopened.calloutTrigger.first()).isEqualTo(CalloutTrigger.GAMES_ONLY)
        }

    @Test
    fun clearingTheCalloutVoiceIdRestoresThePlatformDefault() =
        runTest {
            val settings = DataStoreSettingsRepository(backgroundScope.dataStore())
            settings.setCalloutVoiceId("en-us-x-premium")

            settings.setCalloutVoiceId(null)

            assertThat(settings.calloutVoiceId.first()).isNull()
        }

    @Test
    fun anUnrecognizedStoredCalloutFieldIsDroppedNotCrashed() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            dataStore.edit { it[stringPreferencesKey("calloutFields")] = "CARRY,NOT_A_REAL_FIELD,SMASH" }

            val settings = DataStoreSettingsRepository(dataStore)

            assertThat(settings.calloutFields.first()).isEqualTo(listOf(CalloutField.CARRY, CalloutField.SMASH))
        }

    @Test
    fun anAllUnrecognizedStoredCalloutFieldListFallsBackToTheDefault() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            dataStore.edit { it[stringPreferencesKey("calloutFields")] = "NOT_A_REAL_FIELD" }

            val settings = DataStoreSettingsRepository(dataStore)

            assertThat(settings.calloutFields.first()).isEqualTo(listOf(CalloutField.CARRY, CalloutField.BALL_SPEED))
        }

    @Test
    fun anUnrecognizedStoredCalloutTriggerFallsBackToTheDefault() =
        runTest {
            val dataStore = backgroundScope.dataStore()
            dataStore.edit { it[stringPreferencesKey("calloutTrigger")] = "whenever-it-feels-like-it" }

            val settings = DataStoreSettingsRepository(dataStore)

            assertThat(settings.calloutTrigger.first()).isEqualTo(CalloutTrigger.EVERY_SHOT)
        }
}
