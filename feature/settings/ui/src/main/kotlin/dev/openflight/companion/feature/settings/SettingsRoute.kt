// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.rememberOfMessageHostState
import org.koin.androidx.compose.koinViewModel

/** The settings destination: owns the [SettingsViewModel] and hands [SettingsScreen] its state. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages = rememberOfMessageHostState()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.Message -> messages.show(effect.text)
            }
        }
    }
    SettingsScreen(uiState = uiState, onEvent = viewModel::onEvent, onBack = onBack, messages = messages)
}

/** Test tags for the Android settings screen. */
object SettingsTestTags {
    const val DONE = "settings.done"
    const val UNITS = "settings.units"
    const val CONNECTION = "settings.connection"
    const val PROFILE = "settings.profile"
    const val SIMULATORS = "settings.simulators"
    const val RADAR = "settings.radar"
    const val RADAR_REFRESH = "settings.radar.refresh"
    const val DEBUG_TOGGLE = "settings.debug.toggle"
    const val DEBUG_LOG_PATH = "settings.debug.logPath"
    const val CLOUD_UPLOAD = "settings.cloud.upload"
    const val CLOUD_STATUS = "settings.cloud.status"
    const val SHUTDOWN = "settings.shutdown"
    const val SHUTDOWN_CONFIRM = "settings.shutdown.confirm"
    const val SHUTDOWN_CANCEL = "settings.shutdown.cancel"
    const val SHUTDOWN_PENDING = "settings.shutdown.pending"
    const val SHUTDOWN_DONE = "settings.shutdown.done"
    const val SHUTDOWN_FAILED = "settings.shutdown.failed"
    const val SHUTDOWN_RETRY = "settings.shutdown.retry"
    const val SHUTDOWN_DISMISS = "settings.shutdown.dismiss"
    const val CONNECTION_PROBLEM = "settings.connection.problem"
    const val TRIGGER = "settings.trigger"
    const val TRIGGER_WAITING = "settings.trigger.waiting"
    const val POWER = "settings.power"
    const val POWER_STATE = "settings.power.state"

    /** A radar slider, by its field. */
    fun slider(field: RadarField): String = "settings.radar.${field.name}"

    /** A simulator pill, by its target (e.g. `"gspro"`). */
    fun simulator(target: String): String = "settings.sim.$target"
}
