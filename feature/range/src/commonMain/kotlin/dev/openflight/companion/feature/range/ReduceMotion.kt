// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.runtime.Composable

/**
 * Whether the platform asks for reduced motion, read when the range opens: the range then flies
 * each shot in 0.9 s (the reference's `@Environment(\.accessibilityReduceMotion)`).
 *
 * - Android: animations are off (`Settings.Global.ANIMATOR_DURATION_SCALE` is 0, which is also
 *   what the accessibility "Remove animations" setting does).
 * - iOS: `UIAccessibilityIsReduceMotionEnabled()`.
 */
@Composable
expect fun rememberReduceMotionEnabled(): Boolean
