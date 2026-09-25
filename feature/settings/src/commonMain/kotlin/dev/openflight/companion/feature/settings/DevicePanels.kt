// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One label/value row in a device card. An absent measurement reads [DevicePanels.MISSING]. */
data class DeviceRow(
    val label: String,
    val value: String,
)

/**
 * The Pi's power (Expo `device.tsx` `PowerCard`), shown only once a `power_status` arrived: a Pi
 * without `--battery` never sends one, and "no reading" must not read as an empty battery.
 *
 * @property stateLabel "Plugged in", "On battery", "Battery low", "Battery critical" or "No battery".
 * @property rows charge and voltage only when a battery provider is [PowerStatus.available], then
 *   the provider, then the provider's error when it reported one.
 * @property warning `true` for a low or critical battery, so the card adds a warning icon.
 */
data class PowerCard(
    val stateLabel: String,
    val rows: List<DeviceRow>,
    val warning: Boolean,
)

/**
 * The launch monitor card (Expo `device.tsx` `TriggerCard`). "Nothing reported yet" is not the
 * same claim as "this Pi has no radar", so an unanswered request waits instead of showing zeros.
 */
sealed interface TriggerCard {
    /** No live session to ask (Bluetooth, or not connected): [reason] says why. */
    data class Unavailable(
        val reason: String,
    ) : TriggerCard

    /** Connected, but the Pi hasn't reported its `trigger_status` yet. */
    data object Waiting : TriggerCard

    /** Mode, radar, port, trigger and the trigger totals, in that order. */
    data class Loaded(
        val rows: List<DeviceRow>,
    ) : TriggerCard

    companion object {
        const val WAITING_TEXT: String = "Waiting for the server to report…"
    }
}

/** Pure builders for the device cards (plan R8f), ported from the Expo app's `device.tsx`. */
object DevicePanels {
    /** An absent measurement: an em dash, never a zero. */
    const val MISSING: String = "—"

    private const val MOCK_MODE = "mock"

    /** `POWER_LABEL`. */
    fun powerLabel(state: PowerState): String =
        when (state) {
            PowerState.PLUGGED_IN -> "Plugged in"
            PowerState.ON_BATTERY -> "On battery"
            PowerState.LOW -> "Battery low"
            PowerState.CRITICAL -> "Battery critical"
            PowerState.UNAVAILABLE -> "No battery"
            PowerState.UNKNOWN -> "Unknown"
        }

    /** `MODE_LABEL`; a mode a newer server adds shows as sent. */
    fun modeLabel(mode: String): String =
        when (mode) {
            "rolling-buffer" -> "Rolling buffer"
            MOCK_MODE -> "Mock mode"
            "swing-speed" -> "Swing speed"
            "" -> MISSING
            else -> mode
        }

    /** `null` until the Pi reported its power. */
    fun power(status: PowerStatus?): PowerCard? {
        status ?: return null
        val rows =
            buildList {
                if (status.available) {
                    add(DeviceRow("Charge", status.batteryPercent?.let { "${it.roundToInt()}%" } ?: MISSING))
                    add(DeviceRow("Voltage", status.batteryVoltageV?.let { "${twoDecimals(it)} V" } ?: MISSING))
                }
                add(DeviceRow("Provider", status.provider.ifEmpty { MISSING }))
                status.error?.let { add(DeviceRow("Error", it)) }
            }
        return PowerCard(
            stateLabel = powerLabel(status.state),
            rows = rows,
            warning = status.state == PowerState.LOW || status.state == PowerState.CRITICAL,
        )
    }

    fun trigger(
        status: TriggerStatus?,
        link: PiFeatureAvailability,
    ): TriggerCard =
        when {
            status != null -> TriggerCard.Loaded(triggerRows(status))
            !link.isAvailable -> TriggerCard.Unavailable(link.disabledReason.orEmpty())
            else -> TriggerCard.Waiting
        }

    private fun triggerRows(status: TriggerStatus): List<DeviceRow> {
        // The server reports radar_connected as `monitor is not None and not mock_mode`, so it is
        // false in mock mode even though everything works: say "Simulated", not "Not connected".
        val radar =
            when {
                status.mode == MOCK_MODE -> "Simulated"
                status.radarConnected -> "Connected"
                else -> "Not connected"
            }
        return listOf(
            DeviceRow("Mode", modeLabel(status.mode)),
            DeviceRow("Radar", radar),
            DeviceRow("Port", status.radarPort ?: MISSING),
            DeviceRow("Trigger", status.triggerType ?: MISSING),
            DeviceRow("Triggers seen", status.triggersTotal.toString()),
            DeviceRow("Accepted", status.triggersAccepted.toString()),
            DeviceRow("Rejected", status.triggersRejected.toString()),
        )
    }

    /** JavaScript's `toFixed(2)` for the voltages a battery reports (no `String.format` in common code). */
    @Suppress("MagicNumber")
    internal fun twoDecimals(value: Double): String {
        val hundredths = (abs(value) * 100).roundToLong()
        val sign = if (value < 0 && hundredths != 0L) "-" else ""
        return "$sign${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }
}
