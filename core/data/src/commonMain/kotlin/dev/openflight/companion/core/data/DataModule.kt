// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleShotTransport
import dev.openflight.companion.core.network.PiCameraClient
import dev.openflight.companion.core.network.PiControlClient
import dev.openflight.companion.core.network.WifiShotTransport
import dev.openflight.companion.core.network.openFlightHttpClient
import dev.openflight.companion.core.socketio.KtorWebSocketTransport
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
 * Everything `core:data` provides: [SettingsRepository], [ShotRepository] and
 * [PiSessionRepository] (all singletons; [ShotRepository.start]/[ShotRepository.stop] drive the
 * Pi session too), plus the transports and the one shared [HttpClient] behind them. Includes [platformDataModule],
 * so an app only lists `dataModule`.
 */
val dataModule: Module =
    module {
        includes(platformDataModule, shotHistoryModule)

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
                // R6b: started/stopped with the shot stream; delete/clear reach the Pi through it.
                piSession = get(),
                // R8h: every live shot is also filed in the persistent history.
                persistentHistory = get(),
            )
        }
        // R8d: foreground-only transports. The shell reports the lifecycle; the policy follows it.
        single { AppLifecycle() }
        single {
            LifecycleConnectionPolicy(get<AppLifecycle>().state, get(), CoroutineScope(Dispatchers.Unconfined))
        }
        // R6a: the Wi-Fi-only Socket.IO session API, sharing the HttpClient's engine.
        single<PiSessionRepository> {
            val httpClient = get<HttpClient>()
            DefaultPiSessionRepository(
                settings = get(),
                socketFactory = socketIoPiSocketFactory(KtorWebSocketTransport(httpClient)),
                cameraSource = KtorPiCameraSource(PiCameraClient(httpClient)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                // R8e: profiles, club, processing and power over a schema v2 BLE link.
                bluetooth = get<BleShotTransport>(),
            )
        }
    }
