// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.openflight.companion.core.geodata.WeatherClient
import dev.openflight.companion.core.geodata.WeatherFailure
import dev.openflight.companion.core.geodata.WeatherObservation
import dev.openflight.companion.core.geodata.WeatherResult
import dev.openflight.companion.core.location.LocationAccuracy
import dev.openflight.companion.core.location.LocationProvider
import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okio.IOException

/**
 * [ConditionsRepository] persisted in the settings DataStore (plan F2 A2, extended by F6). MANUAL
 * and AUTO conditions live under separate key prefixes, so switching modes never overwrites the
 * other's last value; every field falls back to [Conditions.ISA] when missing or unreadable.
 * [lastError] and [lastLocation] are in-memory only -- they're transient UI feedback for the
 * current process, not "the last conditions" the plan asks to persist.
 */
@Suppress("TooManyFunctions") // The repository surface, plus its MANUAL/AUTO read/write helpers.
internal class DataStoreConditionsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val locationProvider: LocationProvider,
    private val weatherClient: WeatherClient,
) : ConditionsRepository {
    private val preferences: Flow<Preferences> =
        dataStore.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    private val manualConditions: Flow<Conditions> = preferences.map(::readManualConditions)
    private val autoConditions: Flow<Conditions?> = preferences.map(::readAutoConditions)

    override val mode: StateFlow<ConditionsMode> =
        preferences
            .map { ConditionsMode.fromStorageValue(it[MODE_KEY]) ?: ConditionsMode.MANUAL }
            .stateIn(scope, SharingStarted.Eagerly, ConditionsMode.MANUAL)

    override val conditions: StateFlow<Conditions> =
        combine(mode, manualConditions, autoConditions) { currentMode, manual, auto ->
            if (currentMode == ConditionsMode.AUTO) auto ?: manual else manual
        }.stateIn(scope, SharingStarted.Eagerly, Conditions.ISA)

    override val targetBearing: StateFlow<TargetBearing?> =
        preferences
            .map { prefs -> prefs[BEARING_KEY]?.takeIf { it.isFinite() }?.let(::TargetBearing) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val lastErrorState = MutableStateFlow<ConditionsError?>(null)
    override val lastError: StateFlow<ConditionsError?> = lastErrorState.asStateFlow()

    private val lastLocationState = MutableStateFlow<LocationResult.Fix?>(null)
    override val lastLocation: StateFlow<LocationResult.Fix?> = lastLocationState.asStateFlow()

    override suspend fun setManual(conditions: Conditions) {
        dataStore.edit { prefs ->
            prefs[MODE_KEY] = ConditionsMode.MANUAL.storageValue
            prefs[ALTITUDE_KEY] = conditions.altitudeMeters
            prefs[TEMPERATURE_KEY] = conditions.temperatureC
            prefs.setOrRemove(HUMIDITY_KEY, conditions.humidityPct)
            prefs.setOrRemove(PRESSURE_KEY, conditions.pressureHpa)
            prefs[WIND_SPEED_KEY] = conditions.wind.speedMps
            prefs[WIND_FROM_KEY] = conditions.wind.fromDegrees
            prefs[SURFACE_KEY] = conditions.surface.name
        }
    }

    override suspend fun setTargetBearing(bearing: TargetBearing?) {
        dataStore.edit { it.setOrRemove(BEARING_KEY, bearing?.normalizedDegrees) }
    }

    override suspend fun setSurface(surface: Firmness) {
        dataStore.edit { it[SURFACE_KEY] = surface.name }
    }

    override suspend fun setMode(mode: ConditionsMode) {
        dataStore.edit { it[MODE_KEY] = mode.storageValue }
        if (mode == ConditionsMode.AUTO) fetchAuto()
    }

    /** No-op in MANUAL (plan F6): there's nothing automatic to re-read. */
    override suspend fun refresh() {
        if (mode.value == ConditionsMode.AUTO) fetchAuto()
    }

    /** Plan F6 task 3: location, then weather; on any failure it keeps the last value. */
    private suspend fun fetchAuto() {
        when (val fix = locationProvider.current(LocationAccuracy.COARSE)) {
            is LocationResult.Fix -> fetchWeather(fix)
            LocationResult.PermissionDenied -> fallBackToManual(ConditionsError.LocationPermissionDenied)
            LocationResult.Disabled -> fallBackToManual(ConditionsError.LocationDisabled)
            LocationResult.Unavailable -> fallBackToManual(ConditionsError.LocationUnavailable)
        }
    }

    private suspend fun fetchWeather(fix: LocationResult.Fix) {
        lastLocationState.value = fix
        when (val result = weatherClient.current(fix.lat, fix.lon)) {
            is WeatherResult.Success -> {
                writeAuto(result.observation)
                lastErrorState.value = null
            }

            is WeatherResult.Failure -> {
                // Likely transient (network/timeout/rate-limit/malformed body): stay in AUTO and
                // keep whatever conditions were already showing, per plan F6 task 3.
                lastErrorState.value = ConditionsError.WeatherUnavailable(result.reason.userMessage())
            }
        }
    }

    /**
     * Permission denied or location/device services off aren't transient the way a network
     * hiccup is, so AUTO falls back to MANUAL instead of leaving the user staring at a stale
     * error (plan F6 verification: "denied permission -> MANUAL fallback with a message").
     */
    private suspend fun fallBackToManual(error: ConditionsError) {
        lastErrorState.value = error
        dataStore.edit { it[MODE_KEY] = ConditionsMode.MANUAL.storageValue }
    }

    private suspend fun writeAuto(observation: WeatherObservation) {
        dataStore.edit { prefs ->
            prefs[AUTO_ALTITUDE_KEY] = observation.elevationMeters
            prefs[AUTO_TEMPERATURE_KEY] = observation.tempC
            prefs[AUTO_HUMIDITY_KEY] = observation.humidityPct
            prefs[AUTO_PRESSURE_KEY] = observation.pressureHpa
            prefs[AUTO_WIND_SPEED_KEY] = observation.wind.speedMps
            prefs[AUTO_WIND_FROM_KEY] = observation.wind.fromDegrees
            prefs[AUTO_OBSERVED_AT_KEY] = observation.observedAtEpochMillis
        }
    }

    private fun readManualConditions(prefs: Preferences): Conditions {
        val isa = Conditions.ISA
        return Conditions(
            altitudeMeters = prefs[ALTITUDE_KEY].finiteOr(isa.altitudeMeters),
            temperatureC = prefs[TEMPERATURE_KEY].finiteOr(isa.temperatureC),
            humidityPct = prefs[HUMIDITY_KEY]?.takeIf { it.isFinite() },
            pressureHpa = prefs[PRESSURE_KEY]?.takeIf { it.isFinite() },
            wind =
                Wind(
                    speedMps = prefs[WIND_SPEED_KEY].finiteOr(isa.wind.speedMps),
                    fromDegrees = prefs[WIND_FROM_KEY].finiteOr(isa.wind.fromDegrees),
                ),
            surface = surfaceOf(prefs),
            source = ConditionsSource.MANUAL,
            observedAtEpochMillis = null,
        )
    }

    /** `null` until this install's first successful AUTO fetch. */
    @Suppress("ReturnCount") // One early exit per missing required field reads clearer than nesting.
    private fun readAutoConditions(prefs: Preferences): Conditions? {
        val temperatureC = prefs[AUTO_TEMPERATURE_KEY]?.takeIf { it.isFinite() } ?: return null
        val altitudeMeters = prefs[AUTO_ALTITUDE_KEY]?.takeIf { it.isFinite() } ?: return null
        return Conditions(
            altitudeMeters = altitudeMeters,
            temperatureC = temperatureC,
            humidityPct = prefs[AUTO_HUMIDITY_KEY]?.takeIf { it.isFinite() },
            pressureHpa = prefs[AUTO_PRESSURE_KEY]?.takeIf { it.isFinite() },
            wind =
                Wind(
                    speedMps = prefs[AUTO_WIND_SPEED_KEY].finiteOr(0.0),
                    fromDegrees = prefs[AUTO_WIND_FROM_KEY].finiteOr(0.0),
                ),
            surface = surfaceOf(prefs),
            source = ConditionsSource.WEATHER,
            observedAtEpochMillis = prefs[AUTO_OBSERVED_AT_KEY],
        )
    }

    /** Firmness is one global setting, shared by MANUAL and AUTO (plan F6 task 3: "changes only the surface"). */
    private fun surfaceOf(prefs: Preferences): Firmness =
        Firmness.entries.firstOrNull { it.name == prefs[SURFACE_KEY] } ?: Conditions.ISA.surface

    private fun Double?.finiteOr(fallback: Double): Double = this?.takeIf { it.isFinite() } ?: fallback

    private fun MutablePreferences.setOrRemove(
        key: Preferences.Key<Double>,
        value: Double?,
    ) {
        if (value == null) remove(key) else set(key, value)
    }

    private fun WeatherFailure.userMessage(): String =
        when (this) {
            is WeatherFailure.Network -> message
            WeatherFailure.Timeout -> "the request timed out."
            WeatherFailure.RateLimited -> "too many requests; try again in a few minutes."
            is WeatherFailure.UnexpectedStatus -> "the server returned HTTP $statusCode."
            is WeatherFailure.MalformedResponse -> "the response couldn't be read."
        }

    private companion object {
        // Plan F2/F6: the conditions live in the settings file under their own prefix. MANUAL and
        // AUTO are stored separately so toggling AUTO on and off never loses a manual entry.
        val MODE_KEY = stringPreferencesKey("conditions.mode")
        val SURFACE_KEY = stringPreferencesKey("conditions.surface")
        val BEARING_KEY = doublePreferencesKey("conditions.targetBearingDegrees")

        val ALTITUDE_KEY = doublePreferencesKey("conditions.altitudeMeters")
        val TEMPERATURE_KEY = doublePreferencesKey("conditions.temperatureC")
        val HUMIDITY_KEY = doublePreferencesKey("conditions.humidityPct")
        val PRESSURE_KEY = doublePreferencesKey("conditions.pressureHpa")
        val WIND_SPEED_KEY = doublePreferencesKey("conditions.windSpeedMps")
        val WIND_FROM_KEY = doublePreferencesKey("conditions.windFromDegrees")

        val AUTO_ALTITUDE_KEY = doublePreferencesKey("conditions.auto.altitudeMeters")
        val AUTO_TEMPERATURE_KEY = doublePreferencesKey("conditions.auto.temperatureC")
        val AUTO_HUMIDITY_KEY = doublePreferencesKey("conditions.auto.humidityPct")
        val AUTO_PRESSURE_KEY = doublePreferencesKey("conditions.auto.pressureHpa")
        val AUTO_WIND_SPEED_KEY = doublePreferencesKey("conditions.auto.windSpeedMps")
        val AUTO_WIND_FROM_KEY = doublePreferencesKey("conditions.auto.windFromDegrees")
        val AUTO_OBSERVED_AT_KEY = longPreferencesKey("conditions.auto.observedAtEpochMillis")
    }
}
