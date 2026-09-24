// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * Formats shot measurements for display, ported from `ShotMetricFormatter` in
 * `ios/OpenFlight/DrivingRange/RangeMetricsOverlay.swift` (used by the dashboard and the range).
 *
 * Swift's `value.formatted(.number.precision(.fractionLength(n)))` is locale-aware; this port
 * always uses the en-US form: `,` groups thousands, `.` separates decimals and a hyphen marks
 * negatives. A `null` or non-finite value renders as [MISSING].
 */
object ShotMetricFormatter {
    /** The placeholder shown for a measurement the hardware didn't report. */
    const val MISSING: String = "—"

    fun number(
        value: Double?,
        decimals: Int,
        signed: Boolean = false,
    ): String {
        if (value == null || !value.isFinite()) return MISSING
        require(decimals in 0..MAX_DECIMALS) { "decimals must be in 0..$MAX_DECIMALS, was $decimals" }
        val scale = 10.0.pow(decimals).toLong()
        val scaled = round(abs(value) * scale).toLong()
        val whole = groupThousands((scaled / scale).toString())
        val fraction =
            if (decimals == 0) "" else "." + (scaled % scale).toString().padStart(decimals, '0')
        val sign =
            when {
                value < 0 && scaled != 0L -> "-"
                signed && value > 0 -> "+"
                else -> ""
            }
        return sign + whole + fraction
    }

    private fun groupThousands(digits: String): String =
        digits
            .reversed()
            .chunked(GROUP_SIZE)
            .joinToString(",")
            .reversed()

    private const val GROUP_SIZE = 3
    private const val MAX_DECIMALS = 6
}
