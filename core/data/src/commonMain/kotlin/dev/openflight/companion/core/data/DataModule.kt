// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleShotTransport
import dev.openflight.companion.core.network.PiControlClient
import dev.openflight.companion.core.network.WifiShotTransport
import dev.openflight.companion.core.network.openFlightHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Platform bindings `dataModule` needs:
 * - the single `BleShotTransport` (Android builds it from `androidContext()`, so Android must
 *   start Koin with `androidContext(...)`);
 * - the settings `DataStore<Preferences>` at the platform's file path (`Context.filesDir` on
 *   Android, `NSDocumentDirectory` on iOS).
 */
expect val platformDataModule: Module

/**
 * Everything `core:data` provides: [SettingsRepository] and [ShotRepository] (both singletons),
 * plus the transports and the one shared [HttpClient] behind them. Includes [platformDataModule],
 * so an app only lists `dataModule`.
 */
val dataModule: Module =
    module {
        includes(platformDataModule)

        single<HttpClient> { openFlightHttpClient() }
        single<WifiTransportFactory> {
            val httpClient = get<HttpClient>()
            WifiTransportFactory { host -> WifiShotTransport(host = host, httpClient = httpClient) }
        }
        single { PiControlClient(httpClient = get()) }
        single<SettingsRepository> { DataStoreSettingsRepository(dataStore = get()) }
        single<ShotRepository> {
            DefaultShotRepository(
                settings = get(),
                bluetoothTransport = get<BleShotTransport>(),
                wifiTransportFactory = get(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                piControl = get(),
            )
        }
    }
