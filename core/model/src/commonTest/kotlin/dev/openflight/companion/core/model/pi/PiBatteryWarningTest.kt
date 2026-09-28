// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

/** Issue #48: when the Pi's battery warrants a warning, and what it says. */
class PiBatteryWarningTest {
    private fun status(
        state: PowerState,
        percent: Double? = 18.0,
        available: Boolean = true,
        externalPower: Boolean? = false,
    ) = PowerStatus(
        available = available,
        provider = "geekworm",
        state = state,
        batteryPercent = percent,
        externalPower = externalPower,
    )

    @Test
    fun lowOnBatteryWarnsWithThePercent() {
        val warning = PiBatteryWarning.of(status(PowerState.LOW, percent = 18.0))

        assertThat(warning).isEqualTo(
            PiBatteryWarning(
                level = PiBatteryLevel.LOW,
                percent = 18,
                title = "Pi battery low (18%)",
                detail = PiBatteryWarning.LOW_DETAIL,
                spokenText = "OpenFlight battery low, 18 percent. Connect external power soon.",
            ),
        )
    }

    @Test
    fun criticalOnBatteryWarnsWithThePercent() {
        val warning = PiBatteryWarning.of(status(PowerState.CRITICAL, percent = 9.0))

        assertThat(warning).isEqualTo(
            PiBatteryWarning(
                level = PiBatteryLevel.CRITICAL,
                percent = 9,
                title = "Pi battery critical (9%)",
                detail = PiBatteryWarning.CRITICAL_DETAIL,
                spokenText = "OpenFlight battery critical, 9 percent. Connect external power now.",
            ),
        )
    }

    @Test
    fun theDetailsAreTheKioskWording() {
        assertThat(PiBatteryWarning.LOW_DETAIL).isEqualTo("Connect OpenFlight to external power soon.")
        assertThat(PiBatteryWarning.CRITICAL_DETAIL).isEqualTo("Connect OpenFlight to external power now.")
    }

    @Test
    fun thePercentIsRounded() {
        assertThat(PiBatteryWarning.of(status(PowerState.LOW, percent = 17.5))?.percent).isEqualTo(18)
        assertThat(PiBatteryWarning.of(status(PowerState.LOW, percent = 17.4))?.title)
            .isEqualTo("Pi battery low (17%)")
        assertThat(PiBatteryWarning.of(status(PowerState.CRITICAL, percent = 9.6))?.percent).isEqualTo(10)
    }

    @Test
    fun aMissingPercentLeavesItOut() {
        val low = PiBatteryWarning.of(status(PowerState.LOW, percent = null))
        val critical = PiBatteryWarning.of(status(PowerState.CRITICAL, percent = null))

        assertThat(low?.percent).isNull()
        assertThat(low?.title).isEqualTo(PiBatteryWarning.LOW_TITLE)
        assertThat(low?.spokenText).isEqualTo("OpenFlight battery low. Connect external power soon.")
        assertThat(critical?.title).isEqualTo(PiBatteryWarning.CRITICAL_TITLE)
        assertThat(critical?.spokenText).isEqualTo("OpenFlight battery critical. Connect external power now.")
    }

    @Test
    fun externalPowerSuppressesTheWarning() {
        assertThat(PiBatteryWarning.of(status(PowerState.LOW, externalPower = true))).isNull()
        assertThat(PiBatteryWarning.of(status(PowerState.CRITICAL, externalPower = true))).isNull()
    }

    @Test
    fun anUnknownExternalPowerStillWarns() {
        // The kiosk's rule is `!external_power`: a missing reading doesn't prove it's plugged in.
        assertThat(PiBatteryWarning.of(status(PowerState.LOW, externalPower = null))).isNotNull()
    }

    @Test
    fun anUnavailableMonitorNeverWarns() {
        assertThat(PiBatteryWarning.of(status(PowerState.LOW, available = false))).isNull()
        assertThat(PiBatteryWarning.of(status(PowerState.CRITICAL, available = false))).isNull()
    }

    @Test
    fun otherStatesNeverWarn() {
        for (state in listOf(
            PowerState.ON_BATTERY,
            PowerState.PLUGGED_IN,
            PowerState.UNAVAILABLE,
            PowerState.UNKNOWN,
        )) {
            assertThat(PiBatteryWarning.of(status(state)), "state $state").isNull()
        }
    }

    @Test
    fun noStatusYetNeverWarns() {
        assertThat(PiBatteryWarning.of(null)).isNull()
    }
}
