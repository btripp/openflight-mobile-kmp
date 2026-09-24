// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph. */
val rangeModule: Module =
    module {
        viewModel { DrivingRangeViewModel(shots = get(), settings = get()) }
    }
