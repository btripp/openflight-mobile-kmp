// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfMetricPrimary
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * The metrics over the scene (RangeMetricsOverlay.swift): ball speed and carry on top; at the
 * bottom the club error, the estimated-flight badge and the detail metrics with the "NEXT CLUB"
 * selector, laid out by the shared [detailLayout] (plan F8a2p): one row in landscape, two compact
 * rows of four over a portrait scene, the roomy two-column grid in the docked side panel, and one
 * strip while a ball flies and through the landing dwell ([compactMetrics], plan R7b), so the
 * landing area stays visible.
 *
 * Plan F8a2p: over the scene ([expandToFill]) the ball speed and carry tiles are compact too, so
 * the tee view keeps most of the screen. Every card reports its bounds to [obstructions], which
 * keep the scene's yardage labels and far markers clear of it.
 *
 * @param expandToFill true (the phone overlay, full screen height) pushes the bottom content down
 *   with a growing spacer, so it sits at the bottom of the scene regardless of how tall the top
 *   metrics are. false (the docked side panel, plan F1b, a fixed but possibly short height) uses a
 *   small fixed gap instead: a growing spacer needs the parent's height to be bounded, which a
 *   scrollable dock (for a panel shorter than its content) can't guarantee.
 */
@Composable
internal fun RangeMetricsOverlay(
    uiState: DrivingRangeUiState,
    isLandscape: Boolean,
    onSelectClub: (GolfClub) -> Unit,
    modifier: Modifier = Modifier,
    expandToFill: Boolean = true,
    obstructions: RangeObstructionTracker? = null,
) {
    val shot = uiState.displayedShot
    val layout = uiState.detailLayout(landscape = isLandscape, docked = !expandToFill)
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    start = if (isLandscape) 28.dp else 16.dp,
                    end = if (isLandscape) 28.dp else 16.dp,
                    // Controls (Exit/status/Replay) is now a Column sibling above this overlay
                    // (DrivingRangeScreen.kt), so it already reserves whatever height it needs;
                    // this only needs a small gap, not a fixed offset sized for one line of text.
                    top = OfSpacing.Sm,
                    bottom = 14.dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
    ) {
        Row(
            modifier = Modifier.widthIn(max = if (isLandscape) 540.dp else Dp.Unspecified).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            OfMetricPrimary(
                title = "BALL SPEED",
                value = ShotMetricFormatter.number(shot?.ballSpeedMph, decimals = 1),
                unit = "MPH",
                compact = expandToFill,
                modifier =
                    Modifier
                        .weight(1f)
                        .rangeObstruction("ballSpeed", obstructions)
                        .testTag(RangeTestTags.BALL_SPEED),
            )
            OfMetricPrimary(
                title = "CARRY",
                value = ShotMetricFormatter.number(shot?.estimatedCarryYards, decimals = 0),
                unit = "YDS",
                compact = expandToFill,
                modifier =
                    Modifier
                        .weight(1f)
                        .rangeObstruction("carry", obstructions)
                        .testTag(RangeTestTags.CARRY),
            )
        }
        if (expandToFill) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.height(OfSpacing.Md))
        }
        uiState.club.error?.let { error ->
            Pill(
                text = "⚠ $error",
                color = OfColorTokens.Danger,
                modifier = Modifier.rangeObstruction("clubError", obstructions).testTag(RangeTestTags.CLUB_ERROR),
            )
        }
        if (uiState.usesEstimatedFlight) {
            Pill(
                text = "Estimated flight uses club defaults",
                color = OfColorTokens.Cream,
                modifier = Modifier.rangeObstruction("estimated", obstructions).testTag(RangeTestTags.ESTIMATED),
            )
        }
        if (layout == RangeDetailLayout.STRIP) {
            // Plan R7b: one strip while the ball flies and lands, so the landing area stays visible.
            Pill(
                text = uiState.compactMetricsSummary,
                color = OfColorTokens.Cream,
                modifier = Modifier.rangeObstruction("details", obstructions).testTag(RangeTestTags.METRICS_COMPACT),
            )
        } else {
            DetailMetrics(
                shot,
                uiState.club,
                layout,
                onSelectClub,
                Modifier.rangeObstruction("details", obstructions),
            )
        }
    }
}

@Composable
private fun DetailMetrics(
    shot: ShotEvent?,
    club: RangeClubState,
    layout: RangeDetailLayout,
    onSelectClub: (GolfClub) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = detailMetrics(shot)
    val dense = layout != RangeDetailLayout.GRID
    if (layout == RangeDetailLayout.ROW) {
        Row(
            modifier = modifier.fillMaxWidth().testTag(RangeTestTags.METRICS_DETAIL),
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            ClubMetric(club, onSelectClub, dense = true, Modifier.weight(CLUB_CELL_WEIGHT))
            for (metric in metrics) DetailMetric(metric, dense = true, Modifier.weight(1f))
        }
    } else {
        // The club selector first, then the seven metrics: two rows of four over the scene (plan
        // F8a2p), or four rows of two in the docked panel.
        val cells: List<(@Composable (Modifier) -> Unit)> =
            listOf<@Composable (Modifier) -> Unit>({ ClubMetric(club, onSelectClub, dense, it) }) +
                metrics.map { metric -> { cellModifier: Modifier -> DetailMetric(metric, dense, cellModifier) } }
        Column(
            modifier = modifier.fillMaxWidth().testTag(RangeTestTags.METRICS_DETAIL),
            verticalArrangement = Arrangement.spacedBy(if (dense) OfSpacing.Xs else OfSpacing.Sm),
        ) {
            for (row in cells.chunked(layout.columns)) {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(if (dense) OfSpacing.Xs else OfSpacing.Sm),
                ) {
                    for (cell in row) cell(Modifier.weight(1f).fillMaxHeight())
                    repeat(layout.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ClubMetric(
    club: RangeClubState,
    onSelectClub: (GolfClub) -> Unit,
    dense: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.background(CellBackground, CellShape).padding(if (dense) 2.dp else OfSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OfText(
            text = "NEXT CLUB",
            role = OfTextRole.Eyebrow,
            color = OfColorTokens.CreamDim,
            maxLines = 1,
            modifier =
                Modifier.padding(
                    start = if (dense) OfSpacing.Xs else OfSpacing.Sm,
                    top = if (dense) 2.dp else 0.dp,
                ),
        )
        OfDropdownMenu(
            label = "",
            selected = club.selected.displayName,
            options = GolfClub.entries.map { it.displayName },
            onSelect = { name -> GolfClub.entries.firstOrNull { it.displayName == name }?.let(onSelectClub) },
            enabled = club.selectionEnabled,
            isBusy = club.isChanging,
            compact = dense,
            modifier = Modifier.testTag(RangeTestTags.CLUB_SELECTOR),
        )
    }
}

@Composable
private fun DetailMetric(
    metric: RangeMetricValue,
    dense: Boolean,
    modifier: Modifier,
) {
    if (dense) {
        DenseMetric(metric, modifier)
        return
    }
    OfMetricDetail(
        title = metric.title,
        value = metric.value,
        unit = metric.unit,
        modifier =
            modifier
                .heightIn(min = 45.dp)
                .background(CellBackground, CellShape)
                .padding(horizontal = OfSpacing.Sm, vertical = OfSpacing.Xs),
    )
}

/**
 * Plan F8a2p: a small cell for the dense grid and the landscape row: the title as an eyebrow and
 * the value with its unit on one line, read out as one phrase like [OfMetricDetail].
 */
@Composable
private fun DenseMetric(
    metric: RangeMetricValue,
    modifier: Modifier,
) {
    val missing = metric.value == ShotMetricFormatter.MISSING
    val degrees = metric.unit == "°"
    val shown =
        when {
            missing -> metric.value
            degrees -> metric.value + "°"
            metric.unit.isEmpty() -> metric.value
            else -> "${metric.value} ${metric.unit}"
        }
    Column(
        modifier =
            modifier
                .heightIn(min = DenseCellMinHeight)
                .background(CellBackground, CellShape)
                .padding(horizontal = OfSpacing.Xs, vertical = OfSpacing.Xs)
                .semantics(mergeDescendants = true) { contentDescription = "${metric.title}, $shown" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        OfText(text = metric.title, role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim, maxLines = 1)
        OfText(text = shown, role = OfTextRole.Label, color = OfColorTokens.Cream, maxLines = 1)
    }
}

@Composable
private fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    OfText(
        text = text,
        role = OfTextRole.Label,
        color = color,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

internal data class RangeMetricValue(
    val title: String,
    val value: String,
    val unit: String,
)

/** RangeMetricsOverlay.swift `detailMetrics`. */
internal fun detailMetrics(shot: ShotEvent?): List<RangeMetricValue> =
    listOf(
        RangeMetricValue("CLUB SPEED", ShotMetricFormatter.number(shot?.clubSpeedMph, decimals = 1), "mph"),
        RangeMetricValue("SMASH", ShotMetricFormatter.number(shot?.smashFactor, decimals = 2), ""),
        RangeMetricValue("LAUNCH", ShotMetricFormatter.number(shot?.launchAngleVertical, decimals = 1), "°"),
        RangeMetricValue(
            "DIRECTION",
            ShotMetricFormatter.number(shot?.launchAngleHorizontal, decimals = 1, signed = true),
            "°",
        ),
        RangeMetricValue("SPIN", ShotMetricFormatter.number(shot?.spinRpm, decimals = 0), "rpm"),
        RangeMetricValue("PATH", ShotMetricFormatter.number(shot?.clubPathDeg, decimals = 1, signed = true), "°"),
        RangeMetricValue(
            "SPIN AXIS",
            ShotMetricFormatter.number(shot?.spinAxisDeg, decimals = 1, signed = true),
            "°",
        ),
    )

private const val CLUB_CELL_WEIGHT = 1.4f
private val DenseCellMinHeight = 40.dp
private val CellShape = RoundedCornerShape(12.dp)
private val CellBackground = Color.Black.copy(alpha = 0.55f)
