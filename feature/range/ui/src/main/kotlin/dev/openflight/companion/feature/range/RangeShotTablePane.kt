// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * Tester request 2026-09-30: the current session's shot table on a compact or medium window, as a
 * sheet over the scene (not dimmed). A tapped row opens its shot on the range and closes the sheet.
 */
@Composable
internal fun RangeShotTableSheet(
    table: RangeShotTable,
    onEvent: (DrivingRangeEvent) -> Unit,
    maxHeight: Dp,
) {
    val close = { onEvent(DrivingRangeEvent.ShowTable(open = false)) }
    OfBottomSheet(
        onDismiss = close,
        dimBehind = false,
        maxContentHeight = maxHeight,
        modifier = Modifier.testTag(RangeTestTags.TABLE_PANEL),
    ) {
        RangeShotTableTitle(table, onClose = close)
        RangeShotTableGrid(
            table,
            onOpen = { id ->
                close()
                onEvent(DrivingRangeEvent.Launch(RangeLaunch(table.sessionId.orEmpty(), id)))
            },
        )
    }
}

/**
 * The same table as a side panel beside the scene on an expanded window: it stays open while
 * shots keep flying, and a tapped row opens its shot on the range.
 */
@Composable
internal fun RangeShotTablePanel(
    table: RangeShotTable,
    onEvent: (DrivingRangeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(OfColorTokens.BgCard)
                .testTag(RangeTestTags.TABLE_PANEL),
    ) {
        RangeShotTableTitle(
            table,
            onClose = { onEvent(DrivingRangeEvent.ShowTable(open = false)) },
            modifier = Modifier.padding(start = OfSpacing.Xl, end = OfSpacing.Sm, top = OfSpacing.Sm),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Md),
        ) {
            RangeShotTableGrid(
                table,
                onOpen = { id -> onEvent(DrivingRangeEvent.Launch(RangeLaunch(table.sessionId.orEmpty(), id))) },
            )
        }
    }
}

@Composable
private fun RangeShotTableTitle(
    table: RangeShotTable,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            OfText(text = "Shots", role = OfTextRole.Title)
            OfText(
                text = "This session · ${table.rows.size}",
                role = OfTextRole.Eyebrow,
                color = OfColorTokens.Gold,
            )
        }
        OfOutlinedButton(text = "Done", onClick = onClose, modifier = Modifier.testTag(RangeTestTags.TABLE_CLOSE))
    }
}

/** Header, rows (newest first) and the Avg row, scrolled sideways together. */
@Composable
private fun RangeShotTableGrid(
    table: RangeShotTable,
    onOpen: (String) -> Unit,
) {
    if (table.rows.isEmpty()) {
        OfText(
            text = "No shots in this session yet.",
            role = OfTextRole.Body,
            color = OfColorTokens.CreamDim,
            modifier = Modifier.padding(vertical = OfSpacing.Md),
        )
        return
    }
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        TableLine(table.headers, header = true)
        for (row in table.rows) {
            TableLine(
                row.cells,
                modifier =
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "View on range") { onOpen(row.id) }
                        .testTag(RangeTestTags.tableRow(row.id)),
            )
        }
        table.average?.let { average ->
            TableLine(
                average,
                header = true,
                modifier =
                    Modifier
                        .background(OfColorTokens.BgHover, AverageShape)
                        .testTag(RangeTestTags.TABLE_AVERAGE),
            )
        }
    }
}

@Composable
private fun TableLine(
    cells: List<String>,
    modifier: Modifier = Modifier,
    header: Boolean = false,
) {
    Row(modifier = modifier.padding(vertical = OfSpacing.Xs)) {
        cells.forEachIndexed { index, text ->
            val column = RangeTableColumn.entries[index]
            OfText(
                text = text,
                role = if (header) OfTextRole.BodySmall else OfTextRole.Body,
                color = if (header) OfColorTokens.CreamDim else Color.Unspecified,
                textAlign = if (column == RangeTableColumn.CLUB) TextAlign.Start else TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(column.width()).padding(horizontal = OfSpacing.Xs),
            )
        }
    }
}

private fun RangeTableColumn.width(): Dp =
    when (this) {
        RangeTableColumn.NUMBER -> 36.dp
        RangeTableColumn.CLUB -> 84.dp
        else -> 76.dp
    }

private val AverageShape = RoundedCornerShape(8.dp)

private const val PREVIEW_SHOTS = 3

@Preview(widthDp = 400, heightDp = 600)
@Composable
private fun RangeShotTablePanelPreview() {
    OfTheme { RangeShotTablePanel(previewTable(), onEvent = {}) }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun RangeShotTablePanelTabletPreview() {
    OfTheme {
        Row {
            RangeShotTablePanel(previewTable(), onEvent = {}, modifier = Modifier.width(560.dp))
        }
    }
}

private fun previewTable(): RangeShotTable =
    RangeShotTable.of(
        "s1",
        (PREVIEW_SHOTS downTo 1).map { id ->
            HistoryShot(
                id = id.toLong(),
                sessionId = "s1",
                eventId = null,
                detail =
                    ShotDetail(
                        timestamp = "2026-09-30T17:5$id:00",
                        club = "9-iron",
                        ballSpeedMph = 110.0 + id,
                        clubSpeedMph = 85.4,
                        smashFactor = 1.3,
                        estimatedCarryYards = 160.0 + id,
                        launchAngleVertical = 29.0,
                        launchAngleHorizontal = 0.4,
                        spinRpm = 4_614.0,
                    ),
            )
        },
        RangeNumbers(),
    )
