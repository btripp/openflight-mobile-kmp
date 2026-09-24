// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class PiFeatureAvailabilityTest {
    @Test
    fun onlyAConnectedLinkIsAvailable() {
        val available = PiFeatureAvailability.of(PiLinkState.Connected)

        assertThat(available.isAvailable).isTrue()
        assertThat(available.disabledReason).isNull()
    }

    @Test
    fun bluetoothRequiresWifi() {
        val availability = PiFeatureAvailability.of(PiLinkState.WifiOnly)

        assertThat(availability.isAvailable).isFalse()
        assertThat(availability.disabledReason).isEqualTo("Requires Wi-Fi")
    }

    @Test
    fun anyOtherWifiStateIsNotConnected() {
        val states = listOf(PiLinkState.Idle, PiLinkState.Connecting, PiLinkState.Reconnecting(1, 1_000, "closed"))

        for (state in states) {
            assertThat(PiFeatureAvailability.of(state).disabledReason).isEqualTo("Not connected")
        }
    }
}
