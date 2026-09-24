// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.data.TransportType
import org.koin.androidx.compose.koinViewModel

/**
 * The dashboard destination: owns the [DashboardViewModel] and hands [DashboardScreen] its state.
 *
 * @param transportPermissionRequest composed with the selected transport once settings load. The
 *   app shell supplies the platform's permission prompt (Android runtime permissions; nothing on
 *   iOS, which prompts by itself) and calls `onGranted` after a grant, which retries.
 */
@Composable
fun DashboardRoute(
    onOpenCalibration: () -> Unit,
    onOpenRange: () -> Unit,
    transportPermissionRequest: @Composable (transport: TransportType, onGranted: () -> Unit) -> Unit = { _, _ -> },
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transport by viewModel.selectedTransport.collectAsStateWithLifecycle()
    transport?.let { selected ->
        transportPermissionRequest(selected) { viewModel.onEvent(DashboardEvent.Retry) }
    }
    DashboardScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenCalibration = onOpenCalibration,
        onOpenRange = onOpenRange,
    )
}
