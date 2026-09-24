// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/** The calibration destination: owns the [CalibrationViewModel] and hands [CalibrationScreen] its state. */
@Composable
fun CalibrationRoute(
    onBack: () -> Unit,
    viewModel: CalibrationViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CalibrationScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack)
}
