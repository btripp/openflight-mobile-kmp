// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the platform asks for reduced motion, read when the range opens: the range then flies
 * each shot in 0.9 s (the reference's `@Environment(\.accessibilityReduceMotion)`). On Android
 * that means animations are off (`Settings.Global.ANIMATOR_DURATION_SCALE` is 0, which is also what
 * the accessibility "Remove animations" setting does).
 */
@Composable
internal fun rememberReduceMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
