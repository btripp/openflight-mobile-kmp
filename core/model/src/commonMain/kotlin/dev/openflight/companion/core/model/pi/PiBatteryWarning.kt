// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model.pi

import kotlin.math.roundToInt

/** How urgent a [PiBatteryWarning] is: the backend's `low` (≤ 20 %) and `critical` (≤ 10 %). */
enum class PiBatteryLevel { LOW, CRITICAL }

/**
 * The Pi's battery is running out and nothing is charging it (issue #48): the Dashboard and the
 * range show it, and the app speaks it once per level ([PiBatteryAlertGate]).
 *
 * The rule is the Pi kiosk's own low-battery modal: `available && !external_power && state in
 * {low, critical}`, with its wording as [detail]. The backend (power/service.py) sets the state
 * from the percentage on battery, so the app doesn't re-derive it from [percent].
 *
 * @property percent the battery percentage, rounded, or `null` when the Pi didn't report one.
 * @property title the notice's title, with the percentage when known: "Pi battery low (18%)".
 * @property spokenText the call-out: "OpenFlight battery low, 18 percent. Connect external power soon."
 */
data class PiBatteryWarning(
    val level: PiBatteryLevel,
    val percent: Int?,
    val title: String,
    val detail: String,
    val spokenText: String,
) {
    companion object {
        const val LOW_TITLE = "Pi battery low"
        const val CRITICAL_TITLE = "Pi battery critical"

        /** The kiosk's low-battery text. */
        const val LOW_DETAIL = "Connect OpenFlight to external power soon."

        /** The kiosk's critical-battery text. */
        const val CRITICAL_DETAIL = "Connect OpenFlight to external power now."

        /** The warning [status] calls for, or `null` when there's nothing to warn about. */
        fun of(status: PowerStatus?): PiBatteryWarning? {
            if (status == null || !status.available || status.externalPower == true) return null
            return when (status.state) {
                PowerState.LOW -> build(PiBatteryLevel.LOW, status.batteryPercent)
                PowerState.CRITICAL -> build(PiBatteryLevel.CRITICAL, status.batteryPercent)
                else -> null
            }
        }

        private fun build(
            level: PiBatteryLevel,
            batteryPercent: Double?,
        ): PiBatteryWarning {
            val percent = batteryPercent?.roundToInt()
            val low = level == PiBatteryLevel.LOW
            val title = if (low) LOW_TITLE else CRITICAL_TITLE
            val word = if (low) "low" else "critical"
            val urgency = if (low) "soon" else "now"
            val percentClause = percent?.let { ", $it percent" }.orEmpty()
            return PiBatteryWarning(
                level = level,
                percent = percent,
                title = if (percent == null) title else "$title ($percent%)",
                detail = if (low) LOW_DETAIL else CRITICAL_DETAIL,
                spokenText = "OpenFlight battery $word$percentClause. Connect external power $urgency.",
            )
        }
    }
}

/**
 * Decides when a [PiBatteryWarning] should alert (be spoken) rather than only show (issue #48).
 * The Pi sends a power snapshot every 5 s, so the same warning arrives again and again; this
 * alerts once per level on the way down:
 * - nothing → LOW or CRITICAL, and LOW → CRITICAL, alert;
 * - the same level again, or CRITICAL → LOW (a reading bouncing around the threshold), don't;
 * - no warning (plugged in, or recovered) re-arms it, so a later drop alerts again.
 *
 * Stateful and not thread-safe: feed it from one coroutine.
 */
class PiBatteryAlertGate {
    private var armedAt: PiBatteryLevel? = null

    /** Takes the latest [warning] and returns the level to alert on now, or `null` for none. */
    fun next(warning: PiBatteryWarning?): PiBatteryLevel? {
        val level = warning?.level
        val previous = armedAt
        return when {
            level == null -> {
                armedAt = null
                null
            }

            previous == null || level > previous -> {
                armedAt = level
                level
            }

            else -> {
                null
            }
        }
    }
}
