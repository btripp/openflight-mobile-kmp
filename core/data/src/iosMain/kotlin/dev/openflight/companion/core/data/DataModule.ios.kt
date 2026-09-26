// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleShotTransport
import dev.openflight.companion.core.geodata.WeatherClient
import dev.openflight.companion.core.geodata.weatherClient
import dev.openflight.companion.core.location.LocationProvider
import dev.openflight.companion.core.location.createLocationProvider
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSUserDomainMask

actual val platformDataModule: Module =
    module {
        single { BleShotTransport() }
        single {
            createSettingsDataStore(
                path = "${directoryPath(NSDocumentDirectory)}/${DataStoreSettingsRepository.FILE_NAME}",
            )
        }
        // F6: `CLLocationManager` needs no context to build.
        single<LocationProvider> { createLocationProvider() }
        // F6: the caches directory, not documents -- Open-Meteo reads are disposable and iOS may
        // purge this directory under storage pressure, which is exactly what a 15-minute cache wants.
        single<WeatherClient> {
            weatherClient(httpClient = get<HttpClient>(), cacheDirectory = directoryPath(NSCachesDirectory).toPath())
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun directoryPath(directory: NSSearchPathDirectory): String {
    val url =
        NSFileManager.defaultManager.URLForDirectory(
            directory = directory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    return requireNotNull(url?.path) { "No directory for $directory" }
}
