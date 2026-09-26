// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ScreenReaderMonitor] over [AccessibilityManager] (plan F7 A11y): TalkBack (and any other
 * touch-exploration screen reader) turns on `isTouchExplorationEnabled`, which is the same signal
 * Compose's own accessibility handling treats as "a screen reader is active". Registers both
 * listeners so [isActive] also reflects an accessibility service that reports itself enabled
 * without touch exploration (rare, but cheaper to include than to special-case out).
 *
 * @param context any context; only its application context is kept (same pattern as
 *   [AndroidSpeechEngine]).
 */
class AndroidScreenReaderMonitor(
    context: Context,
) : ScreenReaderMonitor {
    private val manager =
        context.applicationContext.getSystemService(
            Context.ACCESSIBILITY_SERVICE,
        ) as AccessibilityManager

    private val mutableIsActive = MutableStateFlow(manager.isTouchExplorationEnabled)
    override val isActive: StateFlow<Boolean> = mutableIsActive.asStateFlow()

    init {
        val listener =
            AccessibilityManager.TouchExplorationStateChangeListener { enabled -> mutableIsActive.value = enabled }
        manager.addTouchExplorationStateChangeListener(listener)
    }
}
