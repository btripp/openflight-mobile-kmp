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

    // Plan R8e: BLE is read-and-select; destructive actions say "Wi-Fi only" instead of hiding.

    private val wifiOnly = PiFeatureAvailability.Unavailable(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH)

    @Test
    fun deleteAndClearAreWifiOnlyOverBluetoothAndAvailableOtherwise() {
        assertThat(PiFeatureAvailability.forDeleteAndClear(overBluetooth = true)).isEqualTo(wifiOnly)
        assertThat(PiFeatureAvailability.forDeleteAndClear(overBluetooth = false))
            .isEqualTo(PiFeatureAvailability.Available)
    }

    @Test
    fun profileEditsNeedTheSocketIoLink() {
        assertThat(PiFeatureAvailability.forProfileEdits(PiLinkState.WifiOnly)).isEqualTo(wifiOnly)
        assertThat(
            PiFeatureAvailability.forProfileEdits(PiLinkState.Connected),
        ).isEqualTo(PiFeatureAvailability.Available)
        assertThat(PiFeatureAvailability.forProfileEdits(PiLinkState.Connecting))
            .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED))
    }

    @Test
    fun profileSelectionWorksOverSocketIoOrBluetoothSchemaV2() {
        assertThat(PiFeatureAvailability.forProfileSelection(PiLinkState.WifiOnly, bluetoothSchemaV2 = true))
            .isEqualTo(PiFeatureAvailability.Available)
        assertThat(PiFeatureAvailability.forProfileSelection(PiLinkState.WifiOnly, bluetoothSchemaV2 = false))
            .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.REQUIRES_WIFI))
        assertThat(PiFeatureAvailability.forProfileSelection(PiLinkState.Connected, bluetoothSchemaV2 = false))
            .isEqualTo(PiFeatureAvailability.Available)
    }
}
