// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okio.IOException
import okio.Path.Companion.toPath

/** [SettingsRepository] backed by a DataStore preferences file. */
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

    override val host: Flow<String> =
        preferences
            .map { it[HOST_KEY] ?: SettingsRepository.DEFAULT_HOST }
            .distinctUntilChanged()

    override val selectedClub: Flow<GolfClub> =
        preferences
            .map { prefs -> prefs[CLUB_KEY]?.let(GolfClub::fromWireValue) ?: SettingsRepository.DEFAULT_CLUB }
            .distinctUntilChanged()

    override suspend fun setTransport(transport: TransportType) {
        dataStore.edit { it[TRANSPORT_KEY] = transport.storageValue }
    }

    override suspend fun setHost(host: String) {
        dataStore.edit { it[HOST_KEY] = host }
    }

    override suspend fun setSelectedClub(club: GolfClub) {
        dataStore.edit { it[CLUB_KEY] = club.wireValue }
    }

    companion object {
        /** DataStore requires the `.preferences_pb` extension for preferences files. */
        const val FILE_NAME = "openflight.preferences_pb"

        // Same key names as the reference's @AppStorage keys.
        private val TRANSPORT_KEY = stringPreferencesKey("shotTransport")
        private val HOST_KEY = stringPreferencesKey("piHost")
        private val CLUB_KEY = stringPreferencesKey("selectedClub")
    }
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
