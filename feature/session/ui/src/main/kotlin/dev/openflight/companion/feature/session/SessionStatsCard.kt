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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.model.ShotMetricFormatter

// The stats card (`StatsView.tsx`, Expo `stats.tsx`): the shared [sessionStatTiles], three per row,
// or two once the text is scaled up enough that three would squeeze the labels.

@Composable
internal fun StatsCard(uiState: SessionUiState) {
    val perRow = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) TILES_PER_ROW_LARGE_TEXT else TILES_PER_ROW
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SessionTestTags.STATS), contentSpacing = OfSpacing.Md) {
        uiState.statTiles.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                row.forEach { tile -> StatTile(tile, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val TILES_PER_ROW = 3
private const val TILES_PER_ROW_LARGE_TEXT = 2
private const val LARGE_FONT_SCALE = 1.5f

internal fun speedValue(
    mph: Double,
    units: UnitSystem,
): String = ShotMetricFormatter.number(convertSpeedFromMph(mph, units), 1)

@Composable
private fun StatTile(
    tile: SessionStatTile,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(OfColorTokens.BgElevated, RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) { contentDescription = tile.spoken }
                .testTag(SessionTestTags.stat(tile.label))
                .padding(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OfText(text = tile.value, role = OfTextRole.Title, color = OfColorTokens.Cream)
        OfText(text = tile.label, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
    }
}
