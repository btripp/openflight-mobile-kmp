// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen awake while [enabled] is true, so the ~2-second calibration sample doesn't get
 * interrupted by the display sleeping (`View.keepScreenOn`). The SwiftUI app does the same with
 * `UIApplication.idleTimerDisabled`.
 */
@Composable
internal fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
