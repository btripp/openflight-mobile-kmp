// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph. */
val dashboardModule: Module =
    module {
        // Plan R8d: one per process, so the club confirmation shows once per launch, not per screen.
        single { ClubConfirmation() }
        viewModel {
            DashboardViewModel(
                shots = get(),
                settings = get(),
                piSession = get(),
                clubConfirmation = get(),
                // Plan F14.
                demoMode = get(),
                // Issue #15.
                bags = get(),
            )
        }
    }
