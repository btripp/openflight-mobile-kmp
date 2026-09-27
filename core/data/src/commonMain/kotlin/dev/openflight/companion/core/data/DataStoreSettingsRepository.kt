// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.IOException
import okio.Path.Companion.toPath

/** [SettingsRepository] backed by a DataStore preferences file. */
@Suppress("TooManyFunctions") // Mirrors the growing SettingsRepository surface (see its own suppression).
internal class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    private val preferences: Flow<Preferences> =
        dataStore.data.catch { error ->
            // An unreadable file falls back to defaults rather than crashing the dashboard.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    override val transport: Flow<TransportType> =
        preferences
            .map { TransportType.fromStorageValue(it[TRANSPORT_KEY]) ?: SettingsRepository.DEFAULT_TRANSPORT }
            .distinctUntilChanged()

    /** This launch's submitted host (plan R8d); never persisted, see [rememberConnectedHost]. */
    private val submittedHost = MutableStateFlow<String?>(null)

    /** The last host that reached `Connected`, persisted under the reference's `piHost` key. */
    private val lastGoodHost: Flow<String> =
        preferences
            .map { it[HOST_KEY] ?: SettingsRepository.DEFAULT_HOST }
            .distinctUntilChanged()

    override val host: Flow<String> =
        combine(submittedHost, lastGoodHost) { submitted, lastGood -> submitted ?: lastGood }
            .distinctUntilChanged()

    override val selectedClub: Flow<GolfClub> =
        preferences
            .map { prefs -> prefs[CLUB_KEY]?.let(GolfClub::fromWireValue) ?: SettingsRepository.DEFAULT_CLUB }
            .distinctUntilChanged()

    override val units: Flow<UnitSystem> =
        preferences
            .map { prefs -> prefs[UNITS_KEY]?.let(::unitSystemFromStorageValue) ?: SettingsRepository.DEFAULT_UNITS }
            .distinctUntilChanged()

    override val rangeCameraMode: Flow<RangeCameraMode> =
        preferences
            .map { prefs ->
                RangeCameraMode.fromStorageValue(prefs[RANGE_CAMERA_KEY])
                    ?: SettingsRepository.DEFAULT_RANGE_CAMERA_MODE
            }.distinctUntilChanged()

    override suspend fun setTransport(transport: TransportType) {
        dataStore.edit { it[TRANSPORT_KEY] = transport.storageValue }
    }

    override suspend fun setHost(host: String) {
        submittedHost.value = host
    }

    override suspend fun rememberConnectedHost(host: String) {
        dataStore.edit { if (it[HOST_KEY] != host) it[HOST_KEY] = host }
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        dataStore.edit { it[CLUB_KEY] = club.wireValue }
    }

    override suspend fun setUnits(units: UnitSystem) {
        dataStore.edit { it[UNITS_KEY] = units.storageValue() }
    }

    override suspend fun setRangeCameraMode(mode: RangeCameraMode) {
        dataStore.edit { it[RANGE_CAMERA_KEY] = mode.storageValue }
    }

    // Plan F4: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    override val calloutsEnabled: Flow<Boolean> =
        preferences
            .map { it[CALLOUTS_ENABLED_KEY] ?: SettingsRepository.DEFAULT_CALLOUTS_ENABLED }
            .distinctUntilChanged()

    override val calloutVoiceId: Flow<String?> =
        preferences.map { it[CALLOUT_VOICE_ID_KEY] }.distinctUntilChanged()

    override val calloutRate: Flow<Float> =
        preferences
            .map { it[CALLOUT_RATE_KEY] ?: SettingsRepository.DEFAULT_CALLOUT_RATE }
            .distinctUntilChanged()

    override val calloutFields: Flow<List<CalloutField>> =
        preferences
            .map { prefs -> prefs[CALLOUT_FIELDS_KEY]?.let(::calloutFieldsFromStorageValue) }
            .map { it ?: SettingsRepository.DEFAULT_CALLOUT_FIELDS }
            .distinctUntilChanged()

    override val calloutTrigger: Flow<CalloutTrigger> =
        preferences
            .map { prefs ->
                CalloutTrigger.fromStorageValue(prefs[CALLOUT_TRIGGER_KEY])
                    ?: SettingsRepository.DEFAULT_CALLOUT_TRIGGER
            }.distinctUntilChanged()

    override suspend fun setCalloutsEnabled(enabled: Boolean) {
        dataStore.edit { it[CALLOUTS_ENABLED_KEY] = enabled }
    }

    override suspend fun setCalloutVoiceId(voiceId: String?) {
        dataStore.edit { prefs ->
            if (voiceId ==
                null
            ) {
                prefs.remove(CALLOUT_VOICE_ID_KEY)
            } else {
                prefs[CALLOUT_VOICE_ID_KEY] = voiceId
            }
        }
    }

    override suspend fun setCalloutRate(rate: Float) {
        dataStore.edit { it[CALLOUT_RATE_KEY] = rate }
    }

    override suspend fun setCalloutFields(fields: List<CalloutField>) {
        dataStore.edit {
            it[CALLOUT_FIELDS_KEY] =
                fields.joinToString(
                    CALLOUT_FIELDS_SEPARATOR,
                ) { field -> field.name }
        }
    }

    override suspend fun setCalloutTrigger(trigger: CalloutTrigger) {
        dataStore.edit { it[CALLOUT_TRIGGER_KEY] = trigger.storageValue }
    }

    // Plan F8a2a: the range theme, added at the end to keep this file's diff mergeable (§4a A7).

    override val rangeTheme: Flow<RangeThemeSetting> =
        preferences
            .map { RangeThemeSetting.fromStorageValue(it[RANGE_THEME_KEY]) ?: RangeThemeSetting.DEFAULT }
            .distinctUntilChanged()

    override suspend fun setRangeTheme(theme: RangeThemeSetting) {
        dataStore.edit { it[RANGE_THEME_KEY] = theme.storageValue }
    }

    // Plan F8a2t: the shot trail, added at the end to keep this file's diff mergeable (§4a A7).

    override val shotTrail: Flow<ShotTrailStyle> =
        preferences
            .map { ShotTrailStyle.fromStorageValue(it[SHOT_TRAIL_KEY]) ?: ShotTrailStyle.DEFAULT }
            .distinctUntilChanged()

    override suspend fun setShotTrail(style: ShotTrailStyle) {
        dataStore.edit { it[SHOT_TRAIL_KEY] = style.storageValue }
    }

    override val shotTrailKeepLast: Flow<Int> =
        preferences
            .map { stored ->
                stored[SHOT_TRAIL_KEEP_LAST_KEY]?.takeIf { it in SHOT_TRAIL_KEEP_OPTIONS }
                    ?: DEFAULT_SHOT_TRAIL_KEEP_LAST
            }.distinctUntilChanged()

    override suspend fun setShotTrailKeepLast(count: Int) {
        if (count !in SHOT_TRAIL_KEEP_OPTIONS) return
        dataStore.edit { it[SHOT_TRAIL_KEEP_LAST_KEY] = count }
    }

    override val landingEffect: Flow<LandingEffect> =
        preferences
            .map { LandingEffect.fromStorageValue(it[LANDING_EFFECT_KEY]) ?: LandingEffect.DEFAULT }
            .distinctUntilChanged()

    override suspend fun setLandingEffect(effect: LandingEffect) {
        dataStore.edit { it[LANDING_EFFECT_KEY] = effect.storageValue }
    }

    // Plan F14: Demo mode.

    override val demoMode: Flow<Boolean> =
        preferences.map { it[DEMO_MODE_KEY] ?: false }.distinctUntilChanged()

    override suspend fun setDemoMode(enabled: Boolean) {
        dataStore.edit { it[DEMO_MODE_KEY] = enabled }
    }

    override val demoAutoFireSeconds: Flow<Int> =
        preferences
            .map { stored ->
                stored[DEMO_AUTO_FIRE_KEY]?.takeIf { it in DemoModeRepository.AUTO_FIRE_OPTIONS }
                    ?: DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS
            }.distinctUntilChanged()

    override suspend fun setDemoAutoFireSeconds(seconds: Int) {
        if (seconds !in DemoModeRepository.AUTO_FIRE_OPTIONS) return
        dataStore.edit { it[DEMO_AUTO_FIRE_KEY] = seconds }
    }

    companion object {
        /** DataStore requires the `.preferences_pb` extension for preferences files. */
        const val FILE_NAME = "openflight.preferences_pb"

        // Same key names as the reference's @AppStorage keys.
        private val TRANSPORT_KEY = stringPreferencesKey("shotTransport")
        private val HOST_KEY = stringPreferencesKey("piHost")
        private val CLUB_KEY = stringPreferencesKey("selectedClub")

        // Mirrors the web UI's UnitSystem raw values ('imperial'/'metric', useUnitPreferenceStore).
        private val UNITS_KEY = stringPreferencesKey("unitSystem")

        // Plan R7a: the range's follow/fixed camera.
        private val RANGE_CAMERA_KEY = stringPreferencesKey("rangeCameraMode")

        // Plan F4: audio call-outs.
        private val CALLOUTS_ENABLED_KEY = booleanPreferencesKey("calloutsEnabled")
        private val CALLOUT_VOICE_ID_KEY = stringPreferencesKey("calloutVoiceId")
        private val CALLOUT_RATE_KEY = floatPreferencesKey("calloutRate")
        private val CALLOUT_FIELDS_KEY = stringPreferencesKey("calloutFields")
        private val CALLOUT_TRIGGER_KEY = stringPreferencesKey("calloutTrigger")

        // Plan F8a2a: the range theme.
        private val RANGE_THEME_KEY = stringPreferencesKey("rangeTheme")

        // Plan F8a2t: the shot trail.
        private val SHOT_TRAIL_KEY = stringPreferencesKey("shotTrail")
        private val SHOT_TRAIL_KEEP_LAST_KEY = intPreferencesKey("shotTrailKeepLast")
        private val LANDING_EFFECT_KEY = stringPreferencesKey("landingEffect")

        // Plan F14: Demo mode.
        private val DEMO_MODE_KEY = booleanPreferencesKey("demoMode")
        private val DEMO_AUTO_FIRE_KEY = intPreferencesKey("demoAutoFireSeconds")
    }
}

// File-private (not a companion member) so calloutFieldsFromStorageValue below can share it too.
private const val CALLOUT_FIELDS_SEPARATOR = ","

/**
 * Parses the comma-joined [CalloutField] names [setCalloutFields][DataStoreSettingsRepository.setCalloutFields]
 * stores. An unrecognized entry (e.g. from a future app version) is dropped rather than failing
 * the whole read; an empty or all-unrecognized result falls back to the default field list.
 */
private fun calloutFieldsFromStorageValue(stored: String): List<CalloutField>? {
    val fields =
        stored
            .split(CALLOUT_FIELDS_SEPARATOR)
            .mapNotNull { name -> runCatching { CalloutField.valueOf(name) }.getOrNull() }
    return fields.ifEmpty { null }
}

private fun UnitSystem.storageValue(): String =
    when (this) {
        UnitSystem.IMPERIAL -> "imperial"
        UnitSystem.METRIC -> "metric"
    }

private fun unitSystemFromStorageValue(value: String): UnitSystem =
    when (value) {
        "metric" -> UnitSystem.METRIC
        else -> UnitSystem.IMPERIAL
    }

/**
 * Builds the preferences DataStore at [path] (an absolute file path ending in
 * [DataStoreSettingsRepository.FILE_NAME]). Only one instance may exist per file per process, so
 * Koin holds it as a `single`.
 */
internal fun createSettingsDataStore(
    path: String,
    scope: CoroutineScope? = null,
): DataStore<Preferences> =
    if (scope == null) {
        PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })
    } else {
        PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { path.toPath() })
    }
