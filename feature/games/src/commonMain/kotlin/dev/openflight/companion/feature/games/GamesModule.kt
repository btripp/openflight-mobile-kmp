// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph.
 *
 * [GamesViewModel] takes an optional [GameLaunch] parameter (the typed navigation argument, A6):
 * `koinViewModel { parametersOf(launch) }` on Android, `KoinHelper().gamesViewModel(launch)` on
 * iOS. It's wired with an explicit lambda so the injectable clock and id factory keep their
 * defaults.
 */
val gamesModule: Module =
    module {
        viewModel { params ->
            GamesViewModel(
                shots = get(),
                finalShots = get(),
                piSession = get(),
                activeGame = get(),
                activities = get(),
                history = get(),
                conditions = get(),
                settings = get(),
                launch = params.getOrNull<GameLaunch>(),
            )
        }
        viewModel { ActivitiesViewModel(activities = get()) }
    }
