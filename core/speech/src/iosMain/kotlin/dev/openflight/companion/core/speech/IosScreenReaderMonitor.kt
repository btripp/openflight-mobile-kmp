// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

/**
 * [ScreenReaderMonitor] over `UIAccessibilityIsVoiceOverRunning()` (plan F7 A11y). VoiceOver has
 * no Kotlin/Native-friendly push notification without hand-written `NSNotificationCenter`
 * block-observer cinterop, so this polls at [POLL_INTERVAL_MILLIS] instead: a call-out that
 * checks [isActive] right before speaking is at most one interval stale, which is well inside
 * "don't talk over a screen reader"'s tolerance.
 */
class IosScreenReaderMonitor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : ScreenReaderMonitor {
    private val mutableIsActive = MutableStateFlow(UIAccessibilityIsVoiceOverRunning())
    override val isActive: StateFlow<Boolean> = mutableIsActive.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MILLIS)
                mutableIsActive.value = UIAccessibilityIsVoiceOverRunning()
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 500L
    }
}
