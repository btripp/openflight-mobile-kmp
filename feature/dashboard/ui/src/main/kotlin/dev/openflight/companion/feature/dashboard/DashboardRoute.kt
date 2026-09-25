// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    // Plan R5b: a haptic tick and the gold shot-flash for every new shot (never for a replay).
    val haptics = LocalHapticFeedback.current
    var shotFlashes by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                DashboardEffect.NewShot -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    shotFlashes++
                }
            }
        }
    }
    DashboardScreen(
        shotFlashes = shotFlashes,
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onOpenCalibration = onOpenCalibration,
        onOpenRange = onOpenRange,
    )
}
