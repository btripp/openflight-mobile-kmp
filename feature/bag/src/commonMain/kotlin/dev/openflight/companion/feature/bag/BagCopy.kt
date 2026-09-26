// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.insights.GapFlag
import dev.openflight.companion.core.insights.GapInsight
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.TargetBearing
import kotlin.math.abs
import kotlin.math.floor

/** The bag screens' wording, shared by both platforms so Android and iOS say the same thing. */
@Suppress("TooManyFunctions") // Small formatters for one family of screens.
object BagCopy {
    /** The badge on every estimated number (plan §0.2: estimates carry provenance to the UI). */
    const val ESTIMATED_BADGE: String = "est."

    const val WIND_NEEDS_TARGET: String = "Set your target direction to include wind"

    const val HEURISTIC_NOTE: String =
        "Gap suggestions use common club-fitting rules of thumb, not a measurement of your swing."

    const val EXCLUDES_IMPORTED: String = "Imported sessions aren't counted unless you include them."

    /** "155 yds", in the display unit, or "—" when [yards] is `null`. */
    fun distance(
        yards: Double?,
        units: UnitSystem,
    ): String =
        if (yards == null) {
            ShotMetricFormatter.MISSING
        } else {
            ShotMetricFormatter.number(convertDistanceFromYards(yards, units), 0) + " " + distanceUnitLabel(units)
        }

    /** "± 5 yds". */
    fun plusMinus(
        yards: Double,
        units: UnitSystem,
    ): String = "± " + distance(yards, units)

    /** "12 yds right", "4 yds left", "on line". */
    fun side(
        offlineYards: Double,
        units: UnitSystem,
    ): String =
        when {
            abs(convertDistanceFromYards(offlineYards, units)) < ON_LINE_UNITS -> "on line"
            offlineYards > 0 -> distance(offlineYards, units) + " right"
            else -> distance(-offlineYards, units) + " left"
        }

    fun shotCount(count: Int): String = if (count == 1) "1 shot" else "$count shots"

    /** The chip between two rows of My Bag: "12 yds gap", with the flag's word when flagged. */
    fun gapChip(
        gapYards: Double,
        flag: GapFlag?,
        units: UnitSystem,
    ): String {
        val base = distance(abs(gapYards), units) + " gap"
        return when (flag) {
            null -> base
            GapFlag.TOO_TIGHT -> "$base · tight"
            GapFlag.TOO_WIDE -> "$base · wide"
            GapFlag.OUT_OF_ORDER -> "Out of order"
        }
    }

    /** One gapping finding as a sentence. */
    fun insight(
        insight: GapInsight,
        units: UnitSystem,
    ): String =
        when (insight) {
            is GapInsight.TooTight -> {
                "${insight.longer.displayName} and ${insight.shorter.displayName} are only " +
                    "${distance(insight.gapYards, units)} apart. Consider a different loft or dropping one."
            }

            is GapInsight.TooWide -> {
                "${distance(insight.gapYards, units)} between ${insight.longer.displayName} and " +
                    "${insight.shorter.displayName} (over ${distance(
                        insight.limitYards,
                        units,
                    )}). A club in between could fill it."
            }

            is GapInsight.OutOfOrder -> {
                "${insight.longer.displayName} (${distance(insight.longerCarryYards, units)}) carries less than " +
                    "${insight.shorter.displayName} (${distance(insight.shorterCarryYards, units)})."
            }

            is GapInsight.InsufficientData -> {
                "${insight.club.displayName}: ${shotCount(insight.shotCount)}; " +
                    "hit ${insight.requiredShots} for a reliable average."
            }
        }

    /** "Sea level · 15 °C · calm · normal turf" (units per [UnitSystem]). */
    fun conditionsSummary(
        conditions: Conditions,
        targetBearing: TargetBearing?,
        units: UnitSystem,
    ): String {
        val altitude =
            if (abs(conditions.altitudeMeters) < 1.0) {
                "Sea level"
            } else {
                ShotMetricFormatter.number(ConditionsForm.altitudeDisplay(conditions.altitudeMeters, units), 0) +
                    " " + ConditionsForm.altitudeUnit(units)
            }
        val temperature =
            ShotMetricFormatter.number(ConditionsForm.temperatureDisplay(conditions.temperatureC, units), 0) + " " +
                ConditionsForm.temperatureUnit(units)
        val wind =
            if (conditions.wind.isCalm) {
                "calm"
            } else {
                ShotMetricFormatter.number(ConditionsForm.windDisplay(conditions.wind.speedMps, units), 0) + " " +
                    ConditionsForm.windUnit(units) + " from " + compass(conditions.wind.fromDegrees)
            }
        val target = targetBearing?.let { "target " + compass(it.normalizedDegrees) }
        return listOfNotNull(altitude, temperature, wind, surface(conditions.surface), target).joinToString(" · ")
    }

    fun surface(firmness: Firmness): String =
        when (firmness) {
            Firmness.SOFT -> "soft turf"
            Firmness.NORMAL -> "normal turf"
            Firmness.FIRM -> "firm turf"
        }

    fun surfaceOption(firmness: Firmness): String =
        when (firmness) {
            Firmness.SOFT -> "Soft"
            Firmness.NORMAL -> "Normal"
            Firmness.FIRM -> "Firm"
        }

    fun mode(mode: ConditionsMode): String =
        when (mode) {
            ConditionsMode.MANUAL -> "Manual"
            ConditionsMode.AUTO -> "Automatic"
        }

    /** "N", "NE", …: the 8-point compass direction for [degrees]. */
    fun compass(degrees: Double): String {
        val normalized = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        return COMPASS[floor((normalized + HALF_SECTOR) / SECTOR).toInt() % COMPASS.size]
    }

    /** "2026-09-25 14:03" from the Pi's naive local ISO timestamp. */
    fun shotTime(timestamp: String): String = timestamp.take(DATE_TIME_LENGTH).replace('T', ' ')

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private const val FULL_TURN = 360.0
    private const val SECTOR = 45.0
    private const val HALF_SECTOR = 22.5
    private const val ON_LINE_UNITS = 0.5
    private const val DATE_TIME_LENGTH = 16
}
