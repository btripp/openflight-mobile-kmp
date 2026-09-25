// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.rememberOfMessageHostState
import org.koin.androidx.compose.koinViewModel

/**
 * The session destination: owns the [SessionViewModel], hands [SessionScreen] its state, and turns
 * the ViewModel's effects into platform actions.
 *
 * @param onShareCsv hands a finished export ([SessionEffect.CsvReady]) to the platform share sheet.
 *   The app shell supplies it, since only the app owns a `FileProvider` authority.
 */
@Composable
fun SessionRoute(
    onBack: () -> Unit,
    onShareCsv: (csv: String, filename: String) -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    viewModel: SessionViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = rememberOfMessageHostState()
    val share by rememberUpdatedState(onShareCsv)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SessionEffect.CsvReady -> share(effect.csv, effect.filename)
                is SessionEffect.Message -> messages.show(effect.text)
            }
        }
    }
    SessionScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        messages = messages,
        onOpenHistory = onOpenHistory,
    )
}
