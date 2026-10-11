// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole

/**
 * Tester report (build 5, largest text): from this font scale the shot table shows one card per
 * shot instead of columns (the metrics' issue #80 threshold).
 */
internal const val TABLE_CARDS_FONT_SCALE = 1.5f

/** The table at large font scales: one card per shot (and the Avg row), newest first. */
@Composable
internal fun RangeShotTableCards(
    table: RangeShotTable,
    onOpen: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        for (row in table.rows) {
            TableCard(
                table.headers,
                row.cells,
                modifier =
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "View on range") { onOpen(row.id) }
                        .testTag(RangeTestTags.tableRow(row.id)),
            )
        }
        table.average?.let { average ->
            TableCard(
                table.headers,
                average,
                highlighted = true,
                modifier = Modifier.testTag(RangeTestTags.TABLE_AVERAGE),
            )
        }
    }
}

/** "3  Driver" over one "header  value" line per measured column. */
@Composable
private fun TableCard(
    headers: List<String>,
    cells: List<String>,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val named = RangeTableColumn.CLUB.ordinal + 1
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (highlighted) OfColorTokens.BgHover else OfColorTokens.BgDeep, CardShape)
                .padding(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        OfText(
            text = cells.take(named).joinToString("  "),
            role = OfTextRole.Body,
            color = if (highlighted) OfColorTokens.Gold else Color.Unspecified,
        )
        cells.forEachIndexed { index, text ->
            if (index < named) return@forEachIndexed
            Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            ) {
                OfText(
                    text = headers.getOrElse(index) { "" },
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    modifier = Modifier.weight(1f),
                )
                OfText(text = text, role = OfTextRole.Body, maxLines = 1, textAlign = TextAlign.End)
            }
        }
    }
}

private val CardShape = RoundedCornerShape(8.dp)
