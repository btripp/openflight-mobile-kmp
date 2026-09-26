// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import org.koin.androidx.compose.koinViewModel

/**
 * The range destination: owns the [DrivingRangeViewModel] and hands [DrivingRangeScreen] its state.
 *
 * Like the reference, the range suspends (cancelling the flight and any queued shot) when the app
 * leaves the foreground or the screen goes away, and Exit suspends before leaving.
 *
 * @param autoplay fly the displayed shot when the range opens: the `--preview-flight` launch hook.
 * @param reduceMotion the platform's reduced-motion setting, which also fixes the camera. A
 *   parameter so device tests don't depend on the emulator's animation scale.
 * @param replaySessionId plan F8a1: open straight into a replay of this stored session (Session
 *   history detail → "Replay on range"); once, not again after a configuration change.
 * @param windowClass injectable so device tests can force the phone or the tablet layout.
 */
@Composable
fun DrivingRangeRoute(
    onExit: () -> Unit,
    autoplay: Boolean = false,
    viewModel: DrivingRangeViewModel = koinViewModel(),
    reduceMotion: Boolean = rememberReduceMotionEnabled(),
    replaySessionId: String? = null,
    windowClass: OfWindowClass = rememberOfWindowClass(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        onPauseOrDispose { viewModel.suspend() }
    }
    LaunchedEffect(viewModel, reduceMotion) {
        viewModel.onEvent(DrivingRangeEvent.ReduceMotionChanged(reduceMotion))
    }
    LaunchedEffect(viewModel, autoplay) {
        if (autoplay) viewModel.onEvent(DrivingRangeEvent.Replay)
    }
    var replayStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel, replaySessionId) {
        if (replaySessionId != null && !replayStarted) {
            replayStarted = true
            viewModel.onEvent(DrivingRangeEvent.StartReplay(replaySessionId))
        }
    }
    DrivingRangeScreen(
        uiState = uiState,
        reduceMotion = reduceMotion,
        onEvent = viewModel::onEvent,
        onExit = {
            viewModel.suspend()
            onExit()
        },
        windowClass = windowClass,
    )
}
