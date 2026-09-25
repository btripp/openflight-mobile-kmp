// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * One stats tile: [label] (also its test tag), the formatted [value], and what a screen reader
 * says: [spokenLabel] (the label spelled out) and [spokenValue] (the value with its unit, or "not
 * available" for "—"). [spoken] joins them, for a single description.
 */
data class SessionStatTile(
    val label: String,
    val value: String,
    val spokenLabel: String,
    val spokenValue: String,
) {
    val spoken: String get() = "$spokenLabel, $spokenValue"
}

/**
 * The stats card's tiles, shared so Android and iOS show the same ones in the same order.
 *
 * Ball-flight tiles: the kiosk's six (`StatsPanel.tsx`, Expo `stats.tsx`), then plan R8c's minimum
 * ball speed and its sample standard deviation (Expo `sessionStats.ts`). Club speed and smash are
 * averaged only over the shots that report them, so they read "—" when none do. The standard
 * deviation needs two shots and reads "—" before that rather than a misleading 0.
 *
 * Swing sessions show Swings, Last, Best and Average instead.
 */
fun sessionStatTiles(
    stats: ClubStats,
    swingStats: SwingSpeedStats?,
    units: UnitSystem,
): List<SessionStatTile> {
    val speed = speedUnitLabel(units)
    val distance = distanceUnitLabel(units)
    if (swingStats != null) {
        return listOf(
            tile("Swings", swingStats.count.toString(), "Swings"),
            speedTile("Last ($speed)", swingStats.lastSpeedMph, units, "Last swing speed"),
            speedTile("Best ($speed)", swingStats.bestSpeedMph, units, "Best swing speed"),
            speedTile("Average ($speed)", swingStats.avgSpeedMph, units, "Average swing speed"),
        )
    }
    val hasSpread = stats.shotCount >= MIN_SHOTS_FOR_SPREAD
    return listOf(
        tile("Shots", stats.shotCount.toString(), "Shots"),
        speedTile("Avg Ball ($speed)", stats.avgBallSpeedMph, units, "Average ball speed"),
        speedTile("Max Ball ($speed)", stats.maxBallSpeedMph, units, "Maximum ball speed"),
        speedTile(
            "Min Ball ($speed)",
            stats.minBallSpeedMph.takeIf { stats.shotCount > 0 },
            units,
            "Minimum ball speed",
        ),
        speedTile(
            "Ball Std Dev ($speed)",
            stats.stdDevBallSpeedMph.takeIf { hasSpread },
            units,
            "Ball speed standard deviation",
        ),
        tile(
            "Avg Carry ($distance)",
            ShotMetricFormatter.number(convertDistanceFromYards(stats.avgCarryYards, units), 0),
            "Average carry",
            distance,
        ),
        speedTile("Avg Club ($speed)", stats.avgClubSpeedMph, units, "Average club speed"),
        tile("Avg Smash", ShotMetricFormatter.number(stats.avgSmashFactor, 2), "Average smash factor"),
    )
}

/** Below two shots a standard deviation says nothing (Expo `sessionStats.ts` returns 0). */
private const val MIN_SHOTS_FOR_SPREAD = 2

private fun speedTile(
    label: String,
    mph: Double?,
    units: UnitSystem,
    spokenLabel: String,
): SessionStatTile =
    tile(
        label,
        ShotMetricFormatter.number(mph?.let { convertSpeedFromMph(it, units) }, 1),
        spokenLabel,
        speedUnitLabel(units),
    )

private fun tile(
    label: String,
    value: String,
    spokenLabel: String,
    unit: String? = null,
): SessionStatTile {
    val spokenValue =
        when {
            value == ShotMetricFormatter.MISSING -> "not available"
            unit != null -> "$value $unit"
            else -> value
        }
    return SessionStatTile(label = label, value = value, spokenLabel = spokenLabel, spokenValue = spokenValue)
}
