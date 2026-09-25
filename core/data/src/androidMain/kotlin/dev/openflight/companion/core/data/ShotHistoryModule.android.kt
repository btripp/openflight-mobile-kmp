// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.database.buildShotHistoryDatabase
import dev.openflight.companion.core.database.shotHistoryDatabaseBuilder
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformShotHistoryModule: Module =
    module {
        single {
            val context = androidContext()
            ShotHistoryDatabaseOpener { shotHistoryDatabaseBuilder(context).buildShotHistoryDatabase() }
        }
    }
