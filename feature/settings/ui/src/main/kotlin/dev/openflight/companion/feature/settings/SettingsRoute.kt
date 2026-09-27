// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.rememberOfMessageHostState
import dev.openflight.companion.core.insights.CalloutField
import org.koin.androidx.compose.koinViewModel

/** The settings destination: owns the [SettingsViewModel] and hands [SettingsScreen] its state. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenCalibration: (() -> Unit)? = null,
    onOpenCamera: (() -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
    shotTrailPreview: ShotTrailPreview? = null,
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
    SettingsScreen(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        messages = messages,
        onOpenCalibration = onOpenCalibration,
        onOpenCamera = onOpenCamera,
        shotTrailPreview = shotTrailPreview,
    )
}

/** Test tags for the Android settings screen. */
object SettingsTestTags {
    const val UNITS = "settings.units"
    const val CONNECTION = "settings.connection"
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

    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).
    const val CALLOUTS = "settings.callouts"
    const val CALLOUTS_ENABLED = "settings.callouts.enabled"
    const val CALLOUTS_TRIGGER = "settings.callouts.trigger"
    const val CALLOUTS_VOICE = "settings.callouts.voice"
    const val CALLOUTS_PREVIEW_BUTTON = "settings.callouts.previewButton"
    const val CALLOUTS_PREVIEW_TEXT = "settings.callouts.previewText"
    const val CALLOUTS_RATE = "settings.callouts.rate"

    /** A field checklist row, by its field. */
    fun calloutField(field: CalloutField): String = "settings.callouts.field.${field.name}"

    /** A field checklist row's checkbox/toggle chip, by its field. */
    fun calloutFieldToggle(field: CalloutField): String = "settings.callouts.field.${field.name}.toggle"

    /** A field checklist row's "move up" button, by its field. */
    fun calloutFieldMoveUp(field: CalloutField): String = "settings.callouts.field.${field.name}.up"

    /** A field checklist row's "move down" button, by its field. */
    fun calloutFieldMoveDown(field: CalloutField): String = "settings.callouts.field.${field.name}.down"

    // Plan F1d: the Device / Practice / Data groups and the Device group's pushed screens.
    const val GROUP_DEVICE = "settings.group.device"
    const val GROUP_PRACTICE = "settings.group.practice"
    const val GROUP_DATA = "settings.group.data"
    const val OPEN_CALIBRATION = "settings.openCalibration"
    const val OPEN_CAMERA = "settings.openCamera"

    // Plan F8a2a: the range theme picker (Practice group).
    const val RANGE_THEME = "settings.rangeTheme"

    // Plan F8a2t: the shot trail card (Practice group).
    const val SHOT_TRAIL_CARD = "settings.shotTrail.card"
    const val SHOT_TRAIL = "settings.shotTrail"
    const val SHOT_TRAIL_KEEP = "settings.shotTrail.keepLast"
    const val LANDING_EFFECT = "settings.shotTrail.landingEffect"
    const val SHOT_TRAIL_PREVIEW = "settings.shotTrail.preview"

    // Plan F8f: "Show total distance" (plan F5b's toggle, Practice group).
    const val SHOW_TOTAL_DISTANCE = "settings.showTotalDistance"

    // Plan F14: Demo mode (Device group), added at the end to keep this file's diff mergeable.
    const val DEMO_CARD = "settings.demo.card"
    const val DEMO_SWITCH = "settings.demo.switch"
    const val DEMO_AUTO_FIRE = "settings.demo.autoFire"
    const val DEMO_CLEAR = "settings.demo.clear"
    const val DEMO_CLEAR_CONFIRM = "settings.demo.clear.confirm"
    const val DEMO_CLEAR_CANCEL = "settings.demo.clear.cancel"

    /** An automatic-shot interval chip, by its seconds (0 is Off). */
    fun demoAutoFire(seconds: Int): String = "settings.demo.autoFire.$seconds"
}
