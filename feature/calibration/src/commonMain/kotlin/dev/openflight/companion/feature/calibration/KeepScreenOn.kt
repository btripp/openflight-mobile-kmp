// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.runtime.Composable

/**
 * Keeps the screen awake while [enabled] is true, so the ~2-second calibration sample doesn't get
 * interrupted by the display sleeping. Android: `View.keepScreenOn`. iOS:
 * `UIApplication.idleTimerDisabled`.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
