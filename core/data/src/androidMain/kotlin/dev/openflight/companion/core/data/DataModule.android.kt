// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.ble.BleShotTransport
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
    }
