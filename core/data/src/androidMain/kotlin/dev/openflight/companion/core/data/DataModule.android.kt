// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleShotTransport
import dev.openflight.companion.core.geodata.WeatherClient
import dev.openflight.companion.core.geodata.weatherClient
import dev.openflight.companion.core.location.LocationProvider
import dev.openflight.companion.core.location.createLocationProvider
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformDataModule: Module =
    module {
        single { BleShotTransport(context = androidContext()) }
        single {
            createSettingsDataStore(
                path = androidContext().filesDir.resolve(DataStoreSettingsRepository.FILE_NAME).absolutePath,
            )
        }
        // F6: no Play Services dependency, so an F-Droid build stays dependency-free.
        single<LocationProvider> { createLocationProvider(androidContext()) }
        // F6: cacheDir, not filesDir -- Open-Meteo reads are disposable, unlike the settings DataStore.
        single<WeatherClient> {
            weatherClient(
                httpClient = get<HttpClient>(),
                cacheDirectory = androidContext().cacheDir.absolutePath.toPath(),
            )
        }
    }
