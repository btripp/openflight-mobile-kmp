// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.testing

import dev.openflight.companion.core.speech.ScreenReaderMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/** A [ScreenReaderMonitor] a test drives directly (plan F7). Inactive by default. */
class FakeScreenReaderMonitor(
    isActive: Boolean = false,
) : ScreenReaderMonitor {
    override val isActive = MutableStateFlow(isActive)
}
