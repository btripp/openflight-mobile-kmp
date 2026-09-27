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

/**
 * The repositories over the one history database (all singletons; the database opens lazily on
 * first use): [ShotHistoryRepository] (plan R8h), plus [BagRepository] and [ActivityRepository]
 * (plan F3), and (plan F14) Demo mode's [DemoShotHistoryRepository].
 */
internal val shotHistoryModule: Module =
    module {
        includes(platformShotHistoryModule)
        single {
            val opener = get<ShotHistoryDatabaseOpener>()
            HistoryDatabase(
                openDatabase = opener::open,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
        single<ShotHistoryRepository>(DataBindings.Real) {
            DefaultShotHistoryRepository(
                database = get<HistoryDatabase>(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
        // Plan F14: the default the app uses; `shared` binds its Demo-mode switch in its place.
        single<ShotHistoryRepository> { get(DataBindings.Real) }
        // Plan F14: Demo mode's own sessions (source = 'DEMO') in the same database.
        single<DemoShotHistoryRepository>(DataBindings.DemoHistory) {
            DefaultDemoShotHistoryRepository(
                DefaultShotHistoryRepository(
                    database = get<HistoryDatabase>(),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    demoWorld = true,
                ),
            )
        }
        single<BagRepository> { DefaultBagRepository(database = get()) }
        single<ActivityRepository> { DefaultActivityRepository(database = get()) }
    }
