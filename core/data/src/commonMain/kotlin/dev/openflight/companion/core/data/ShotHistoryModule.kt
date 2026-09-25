// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds [ShotHistoryDatabaseOpener]: Android builds the file under `Context.getDatabasePath` (so
 * Koin must be started with `androidContext(...)`, as for the settings DataStore), iOS under
 * `NSDocumentDirectory`.
 */
internal expect val platformShotHistoryModule: Module

/** [ShotHistoryRepository] (a singleton; the database opens lazily on first use). */
internal val shotHistoryModule: Module =
    module {
        includes(platformShotHistoryModule)
        single<ShotHistoryRepository> {
            val opener = get<ShotHistoryDatabaseOpener>()
            DefaultShotHistoryRepository(
                openDatabase = opener::open,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
