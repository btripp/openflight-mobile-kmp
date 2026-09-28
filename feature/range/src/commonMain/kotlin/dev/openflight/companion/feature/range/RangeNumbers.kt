// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.DEFAULT_SHOW_TOTAL_DISTANCE
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter
import kotlin.math.roundToInt

/**
 * How the range shows its numbers (plan F8f's quick settings "Numbers"): the persisted
 * [SettingsRepository.units] and [SettingsRepository.showTotalDistance], with the display
 * formatting both platforms use, so a change in the panel or in Settings › Practice shows at once.
 * Wire values stay in the imperial units they arrive in; only the text is converted.
 *
 * @property showTotal show the estimated total and roll-out (always labelled "est.", plan §0.2):
 *   the chip under the controls and the dashed roll-out on the scene.
 */
data class RangeNumbers(
    val units: UnitSystem = SettingsRepository.DEFAULT_UNITS,
    val showTotal: Boolean = DEFAULT_SHOW_TOTAL_DISTANCE,
) {
    /** "mph" or "km/h". */
    val speedUnit: String get() = speedUnitLabel(units)

    /** "yds" or "m": the metric tiles' unit. */
    val distanceUnit: String get() = if (units == UnitSystem.METRIC) "m" else "yds"

    /** A speed in [units], or "—" when missing: "151.4" / "243.7". */
    fun speed(
        mph: Double?,
        decimals: Int = 1,
    ): String = ShotMetricFormatter.number(mph?.let { convertSpeedFromMph(it, units) }, decimals)

    /** A distance in [units], or "—" when missing: "264" / "241". */
    fun distance(
        yards: Double?,
        decimals: Int = 0,
    ): String = ShotMetricFormatter.number(yards?.let { convertDistanceFromYards(it, units) }, decimals)

    /** The estimated total and roll-out chip: "Total est. 285 yd · roll 21" (both always estimates). */
    fun rollOutSummary(rollOut: RangeRollOut): String {
        val unit = if (units == UnitSystem.METRIC) "m" else "yd"
        val total = convertDistanceFromYards(rollOut.totalYards, units).roundToInt()
        val roll = convertDistanceFromYards(rollOut.rollYards, units).roundToInt()
        return "Total est. $total $unit · roll $roll" + if (rollOut.carryEstimated) " · carry est." else ""
    }
}
