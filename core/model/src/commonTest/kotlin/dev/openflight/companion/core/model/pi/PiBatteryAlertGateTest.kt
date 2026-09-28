// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/** Issue #48: when a battery warning should alert (speak) rather than just show. */
class PiBatteryAlertGateTest {
    private val gate = PiBatteryAlertGate()

    private fun warning(level: PiBatteryLevel): PiBatteryWarning =
        checkNotNull(
            PiBatteryWarning.of(
                PowerStatus(
                    available = true,
                    state = if (level == PiBatteryLevel.LOW) PowerState.LOW else PowerState.CRITICAL,
                    batteryPercent = if (level == PiBatteryLevel.LOW) 18.0 else 9.0,
                    externalPower = false,
                ),
            ),
        )

    private val low = warning(PiBatteryLevel.LOW)
    private val critical = warning(PiBatteryLevel.CRITICAL)

    @Test
    fun nothingToNothingIsSilent() {
        assertThat(gate.next(null)).isNull()
        assertThat(gate.next(null)).isNull()
    }

    @Test
    fun noneToLowAlertsLow() {
        assertThat(gate.next(null)).isNull()
        assertThat(gate.next(low)).isEqualTo(PiBatteryLevel.LOW)
    }

    @Test
    fun noneToCriticalAlertsCritical() {
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
    }

    @Test
    fun lowToCriticalAlertsAgain() {
        assertThat(gate.next(low)).isEqualTo(PiBatteryLevel.LOW)
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
    }

    @Test
    fun criticalToLowDoesNotNagDownward() {
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
        assertThat(gate.next(low)).isNull()
        // Still armed at CRITICAL: bouncing back up isn't a new alert either.
        assertThat(gate.next(critical)).isNull()
    }

    @Test
    fun repeatedSnapshotsAtOneLevelAlertOnce() {
        assertThat(gate.next(low)).isEqualTo(PiBatteryLevel.LOW)
        repeat(5) { assertThat(gate.next(low)).isNull() }
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
        repeat(5) { assertThat(gate.next(critical)).isNull() }
    }

    @Test
    fun recoveringReArmsSoALaterDropAlertsAgain() {
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
        assertThat(gate.next(null)).isNull()
        assertThat(gate.next(low)).isEqualTo(PiBatteryLevel.LOW)
        assertThat(gate.next(null)).isNull()
        assertThat(gate.next(critical)).isEqualTo(PiBatteryLevel.CRITICAL)
    }
}
