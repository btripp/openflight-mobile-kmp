// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * A confidence badge, as the web UI's `MetricCard` draws it (`ShotDisplay.tsx`): a label and up
 * to [MAX_DOTS] dots, [filledDots] of them filled. [EXPERIMENTAL] shows the label only.
 */
enum class ConfidenceLevel(
    /** The web UI's badge text. */
    val label: String,
    val filledDots: Int,
) {
    HIGH("high", ALL_DOTS),
    MEDIUM("medium", 2),
    LOW("low", 1),
    EXPERIMENTAL("experimental", 0),
    ;

    val showsDots: Boolean get() = this != EXPERIMENTAL

    companion object {
        const val MAX_DOTS: Int = ALL_DOTS
        private const val HIGH_LAUNCH_CONFIDENCE = 0.7
        private const val MEDIUM_LAUNCH_CONFIDENCE = 0.4

        /** `getLaunchAngleQuality` (`ShotDisplay.tsx`): ≥ 0.7 high, ≥ 0.4 medium, otherwise low. */
        fun fromLaunchAngleConfidence(confidence: Double?): ConfidenceLevel? =
            when {
                confidence == null -> null
                confidence >= HIGH_LAUNCH_CONFIDENCE -> HIGH
                confidence >= MEDIUM_LAUNCH_CONFIDENCE -> MEDIUM
                else -> LOW
            }

        /** The server's `spin_quality` (`high`, `medium`, `low`, `experimental`); `null` for anything else. */
        fun fromSpinQuality(quality: String?): ConfidenceLevel? = entries.firstOrNull { it.label == quality }
    }
}

private const val ALL_DOTS = 3

/** Where a spin rate came from (`spin_source`). */
enum class SpinSource(
    /** The web UI's subtext under the spin rate: `measured` shows "radar", `calculated` "estimated". */
    val label: String,
) {
    MEASURED("radar"),
    ESTIMATED("estimated"),
    ;

    companion object {
        fun fromWire(source: String?): SpinSource? =
            when (source) {
                "measured" -> MEASURED
                "calculated" -> ESTIMATED
                else -> null
            }
    }
}

/**
 * The extra detail the Pi's Socket.IO API knows about a shot (plan R6b) and the SSE/BLE
 * [dev.openflight.companion.core.model.ShotEvent] doesn't: confidence badges, the carry range, the
 * spin-adjusted carry and the player. Every field is optional, mirroring what `ShotDisplay.tsx`
 * shows only when present.
 *
 * @property launchAngleConfidence the launch-angle badge, only when a launch angle was reported.
 * @property angleSource `radar`, `camera`, `estimated` or `mock`: the launch-angle subtext.
 * @property spinQuality the spin badge, only when a spin rate was reported.
 * @property spinSource only when a spin rate was reported.
 * @property carrySpinAdjustedYards shown instead of the estimated carry when present (subtext
 *   "spin-adjusted"); otherwise the carry range is the subtext.
 */
data class ShotEnrichment(
    val launchAngleConfidence: ConfidenceLevel?,
    val angleSource: String?,
    val spinQuality: ConfidenceLevel?,
    val spinSource: SpinSource?,
    val carryRangeLowYards: Double?,
    val carryRangeHighYards: Double?,
    val carrySpinAdjustedYards: Double?,
    val playerName: String?,
) {
    val hasCarryRange: Boolean get() = carryRangeLowYards != null && carryRangeHighYards != null

    /** `"231-255 yds"` (`formatCarryRange`, `units.ts`), or `null` without a range. */
    fun carryRangeText(units: UnitSystem): String? {
        val low = carryRangeLowYards
        val high = carryRangeHighYards
        return if (low == null || high == null) null else formatCarryRange(low, high, units)
    }

    companion object {
        /** Builds the enrichment for one shot, following `ShotDisplay.tsx`'s presence rules. */
        fun from(detail: ShotDetail): ShotEnrichment {
            val hasSpin = detail.spinRpm != null
            return ShotEnrichment(
                launchAngleConfidence =
                    ConfidenceLevel
                        .fromLaunchAngleConfidence(detail.launchAngleConfidence)
                        .takeIf { detail.launchAngleVertical != null },
                angleSource = detail.angleSource,
                spinQuality = ConfidenceLevel.fromSpinQuality(detail.spinQuality).takeIf { hasSpin },
                spinSource = SpinSource.fromWire(detail.spinSource).takeIf { hasSpin },
                carryRangeLowYards = detail.carryRangeLow,
                carryRangeHighYards = detail.carryRangeHigh,
                // The web UI tests it for truthiness, so 0 counts as absent.
                carrySpinAdjustedYards = detail.carrySpinAdjusted?.takeIf { it != 0.0 },
                playerName = detail.playerName,
            )
        }
    }
}

/** `"<low>-<high> <unit>"`, both rounded to whole units, e.g. "231-255 yds" (`formatCarryRange`, `units.ts`). */
fun formatCarryRange(
    lowYards: Double,
    highYards: Double,
    unitSystem: UnitSystem,
): String {
    val low = ShotMetricFormatter.number(convertDistanceFromYards(lowYards, unitSystem), 0)
    val high = ShotMetricFormatter.number(convertDistanceFromYards(highYards, unitSystem), 0)
    return "$low-$high ${distanceUnitLabel(unitSystem)}"
}
