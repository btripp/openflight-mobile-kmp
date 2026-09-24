// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph.
 *
 * Wired with an explicit lambda rather than `viewModelOf(::SessionViewModel)`: the latter tries
 * to `get()` every constructor parameter, including [SessionViewModel]'s injectable `now: () ->
 * String` default (same issue `feature:calibration`'s `CalibrationViewModel` hit with its
 * `deviceModelProvider` default).
 */
val sessionModule: Module =
    module {
        viewModel { SessionViewModel(shots = get(), settings = get()) }
    }
