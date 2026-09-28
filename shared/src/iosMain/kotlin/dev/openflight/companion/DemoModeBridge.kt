// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import dev.openflight.companion.core.data.DemoModeRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Plan F14: Demo mode as the SwiftUI shell sees it, for the persistent Demo badge (Android's
 * `DemoBanner` reads [DemoModeRepository] straight from Koin). Get one from `KoinHelper().demoMode()`.
 */
class DemoModeBridge internal constructor(
    private val demo: DemoModeRepository,
) {
    /** Swift: `enabled` (the current value) and `enabledFlow`. */
    @NativeCoroutinesState
    val enabled: StateFlow<Boolean> get() = demo.enabled
}

/**
 * Plan F14 (`--callout-probe`): the last call-out [CalloutProbeSpeechEngine] wrote down, for the
 * iOS UI tests. Get one from `KoinHelper().calloutProbe()`.
 */
class CalloutProbeBridge internal constructor(
    private val probe: CalloutProbeSpeechEngine,
) {
    /** Swift: `lastSpoken` (the current value) and `lastSpokenFlow`. */
    @NativeCoroutinesState
    val lastSpoken: StateFlow<String?> get() = probe.lastSpoken
}
