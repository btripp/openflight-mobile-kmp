// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import android.provider.Settings
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the system's "Remove animations" setting is on (the animator duration scale is 0), like
 * `feature:range:ui`'s check. Plan R8f: the session lists then don't animate rows in and out.
 */
@Composable
internal fun rememberReduceMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** [LazyItemScope.animateItem], unless [reduceMotion]. */
internal fun LazyItemScope.animateItemUnless(reduceMotion: Boolean): Modifier =
    if (reduceMotion) Modifier else Modifier.animateItem()
