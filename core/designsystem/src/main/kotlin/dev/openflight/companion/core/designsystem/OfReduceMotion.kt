// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user turned animations off (Settings > Accessibility > Remove animations, which
 * sets the animator duration scale to 0). Components swap motion for a static equivalent then,
 * for example [OfNotice]'s spinner (plan R8f accessibility pass).
 */
@Composable
fun rememberOfReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
