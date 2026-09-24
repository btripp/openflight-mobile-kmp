// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph. */
val settingsModule: Module =
    module {
        viewModelOf(::SettingsViewModel)
    }
