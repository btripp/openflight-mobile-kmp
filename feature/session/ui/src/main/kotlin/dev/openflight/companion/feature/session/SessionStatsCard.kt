// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

// The stats card (`StatsView.tsx`): ball-flight tiles, or swing-speed tiles for a swing session.

@Composable
internal fun StatsCard(uiState: SessionUiState) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SessionTestTags.STATS), contentSpacing = OfSpacing.Md) {
        val swing = uiState.swingStats
        val tiles = if (swing != null) swingTiles(swing, uiState.units) else ballTiles(uiState.stats, uiState.units)
        tiles.chunked(TILES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                repeat(TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val TILES_PER_ROW = 3

/** `StatsView.tsx`'s ball-flight tiles; club speed and smash only when some shot reported them. */
private fun ballTiles(
    stats: ClubStats,
    units: UnitSystem,
): List<Pair<String, String>> {
    val speed = speedUnitLabel(units)
    val distance = distanceUnitLabel(units)
    return buildList {
        add("Shots" to stats.shotCount.toString())
        add("Avg Ball ($speed)" to speedValue(stats.avgBallSpeedMph, units))
        add("Max Ball ($speed)" to speedValue(stats.maxBallSpeedMph, units))
        add(
            "Avg Carry ($distance)" to
                ShotMetricFormatter.number(convertDistanceFromYards(stats.avgCarryYards, units), 0),
        )
        stats.avgClubSpeedMph?.let { add("Avg Club ($speed)" to speedValue(it, units)) }
        stats.avgSmashFactor?.let { add("Avg Smash" to ShotMetricFormatter.number(it, 2)) }
    }
}

/** `StatsView.tsx`'s swing-speed tiles: Swings, Last, Best and Average. */
private fun swingTiles(
    stats: SwingSpeedStats,
    units: UnitSystem,
): List<Pair<String, String>> {
    val speed = speedUnitLabel(units)
    return listOf(
        "Swings" to stats.count.toString(),
        "Last ($speed)" to speedValue(stats.lastSpeedMph, units),
        "Best ($speed)" to speedValue(stats.bestSpeedMph, units),
        "Average ($speed)" to speedValue(stats.avgSpeedMph, units),
    )
}

internal fun speedValue(
    mph: Double,
    units: UnitSystem,
): String = ShotMetricFormatter.number(convertSpeedFromMph(mph, units), 1)

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(OfColorTokens.BgElevated, RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) { contentDescription = "$label, $value" }
                .testTag(SessionTestTags.stat(label))
                .padding(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OfText(text = value, role = OfTextRole.Title, color = OfColorTokens.Cream, maxLines = 1)
        OfText(text = label, role = OfTextRole.Label, color = OfColorTokens.CreamDim, maxLines = 2)
    }
}
