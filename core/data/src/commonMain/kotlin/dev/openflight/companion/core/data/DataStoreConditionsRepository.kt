// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okio.IOException

/**
 * MANUAL-only [ConditionsRepository] persisted in the settings DataStore (plan F2, A2). Every
 * value falls back to [Conditions.ISA] when missing or unreadable. F6 adds the AUTO source.
 */
internal class DataStoreConditionsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : ConditionsRepository {
    private val preferences: Flow<Preferences> =
        dataStore.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    override val conditions: StateFlow<Conditions> =
        preferences.map(::readConditions).stateIn(scope, SharingStarted.Eagerly, Conditions.ISA)

    override val mode: StateFlow<ConditionsMode> =
        preferences
            .map { ConditionsMode.fromStorageValue(it[MODE_KEY]) ?: ConditionsMode.MANUAL }
            .stateIn(scope, SharingStarted.Eagerly, ConditionsMode.MANUAL)

    override val targetBearing: StateFlow<TargetBearing?> =
        preferences
            .map { prefs -> prefs[BEARING_KEY]?.takeIf { it.isFinite() }?.let(::TargetBearing) }
            .stateIn(scope, SharingStarted.Eagerly, null)

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

    /** MANUAL has nothing to re-read. */
    override suspend fun refresh() = Unit

    private fun readConditions(prefs: Preferences): Conditions {
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
            surface = Firmness.entries.firstOrNull { it.name == prefs[SURFACE_KEY] } ?: isa.surface,
            source = ConditionsSource.MANUAL,
            observedAtEpochMillis = null,
        )
    }

    private fun Double?.finiteOr(fallback: Double): Double = this?.takeIf { it.isFinite() } ?: fallback

    private fun MutablePreferences.setOrRemove(
        key: Preferences.Key<Double>,
        value: Double?,
    ) {
        if (value == null) remove(key) else set(key, value)
    }

    private companion object {
        // Plan F2: the conditions live in the settings file under their own prefix.
        val MODE_KEY = stringPreferencesKey("conditions.mode")
        val ALTITUDE_KEY = doublePreferencesKey("conditions.altitudeMeters")
        val TEMPERATURE_KEY = doublePreferencesKey("conditions.temperatureC")
        val HUMIDITY_KEY = doublePreferencesKey("conditions.humidityPct")
        val PRESSURE_KEY = doublePreferencesKey("conditions.pressureHpa")
        val WIND_SPEED_KEY = doublePreferencesKey("conditions.windSpeedMps")
        val WIND_FROM_KEY = doublePreferencesKey("conditions.windFromDegrees")
        val SURFACE_KEY = stringPreferencesKey("conditions.surface")
        val BEARING_KEY = doublePreferencesKey("conditions.targetBearingDegrees")
    }
}
