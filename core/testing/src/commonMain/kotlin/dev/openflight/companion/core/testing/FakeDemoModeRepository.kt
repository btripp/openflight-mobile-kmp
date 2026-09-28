// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.data.DemoModeRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Plan F14: a [DemoModeRepository] over plain state flows that counts its commands. */
class FakeDemoModeRepository(
    enabled: Boolean = false,
) : DemoModeRepository {
    override val enabled = MutableStateFlow(enabled)
    override val autoFireSeconds = MutableStateFlow(DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS)

    var hitShotCalls = 0
        private set
    var clearCalls = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setAutoFireSeconds(seconds: Int) {
        if (seconds in DemoModeRepository.AUTO_FIRE_OPTIONS) autoFireSeconds.value = seconds
    }

    override suspend fun hitShot() {
        hitShotCalls++
    }

    override suspend fun clearDemoData() {
        clearCalls++
    }
}
