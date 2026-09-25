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
        viewModel { SessionViewModel(shots = get(), settings = get(), piSession = get()) }
        // Plan R8h: the stored session list, and one stored session (its id as the parameter).
        viewModel { SessionHistoryViewModel(history = get()) }
        viewModel { params ->
            SessionHistoryDetailViewModel(sessionId = params.get(), history = get(), settings = get())
        }
    }
