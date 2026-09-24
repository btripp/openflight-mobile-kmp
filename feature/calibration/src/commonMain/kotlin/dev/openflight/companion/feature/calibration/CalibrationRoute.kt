// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The calibration destination: owns the [CalibrationViewModel] and hands [CalibrationScreen] its state. */
@Composable
fun CalibrationRoute(
    onBack: () -> Unit,
    viewModel: CalibrationViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CalibrationScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}

/**
 * The platform binding this feature needs: a `GravitySensor` singleton. Android's factory needs
 * `androidContext()`, so — like `core:data`'s `platformDataModule` — this is expect/actual rather
 * than plain commonMain.
 */
expect val platformCalibrationModule: Module

/**
 * Koin bindings for this feature. Needs `core:data`'s `dataModule` in the same graph. Uses the
 * `viewModel { }` lambda builder rather than `viewModelOf(::CalibrationViewModel)`: the latter
 * resolves every constructor parameter from the graph by type, including
 * `deviceModelProvider`'s `() -> String`, which nothing provides.
 */
val calibrationModule: Module =
    module {
        includes(platformCalibrationModule)
        viewModel { CalibrationViewModel(shots = get(), settings = get(), gravitySensor = get()) }
    }
