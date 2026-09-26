// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for this feature (plan F5). Needs `core:data`'s `dataModule` in the same graph.
 * Explicit lambdas rather than `viewModelOf`, so the dispatcher parameters keep their defaults.
 */
val bagModule: Module =
    module {
        viewModel {
            BagViewModel(bagRepository = get(), history = get(), conditionsRepository = get(), settings = get())
        }
        viewModel { ClubAnalysisViewModel(bags = get(), history = get(), conditions = get(), settings = get()) }
        // The club's wire value (e.g. "7-iron") as the parameter.
        viewModel { params ->
            ClubDetailViewModel(wireValue = params.get(), history = get(), conditions = get(), settings = get())
        }
    }
