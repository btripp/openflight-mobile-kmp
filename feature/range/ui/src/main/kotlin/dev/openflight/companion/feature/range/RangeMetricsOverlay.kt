// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
 * selector, in one row in landscape or a two-column grid in portrait. While a ball flies and
 * through the landing dwell ([compactMetrics], plan R7b) the detail metrics fold into one strip so
 * the landing area stays visible.
 */
@Composable
internal fun RangeMetricsOverlay(
    uiState: DrivingRangeUiState,
    isLandscape: Boolean,
    onSelectClub: (GolfClub) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shot = uiState.displayedShot
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
                modifier = Modifier.weight(1f).testTag(RangeTestTags.BALL_SPEED),
            )
            OfMetricPrimary(
                title = "CARRY",
                value = ShotMetricFormatter.number(shot?.estimatedCarryYards, decimals = 0),
                unit = "YDS",
                modifier = Modifier.weight(1f).testTag(RangeTestTags.CARRY),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        uiState.club.error?.let { error ->
            Pill(text = "⚠ $error", color = OfColorTokens.Danger, modifier = Modifier.testTag(RangeTestTags.CLUB_ERROR))
        }
        if (uiState.usesEstimatedFlight) {
            Pill(
                text = "Estimated flight uses club defaults",
                color = OfColorTokens.Cream,
                modifier = Modifier.testTag(RangeTestTags.ESTIMATED),
            )
        }
        if (uiState.compactMetrics) {
            // Plan R7b: one strip while the ball flies and lands, so the landing area stays visible.
            Pill(
                text = uiState.compactMetricsSummary,
                color = OfColorTokens.Cream,
                modifier = Modifier.testTag(RangeTestTags.METRICS_COMPACT),
            )
        } else {
            DetailMetrics(shot, uiState.club, isLandscape, onSelectClub)
        }
    }
}

@Composable
private fun DetailMetrics(
    shot: ShotEvent?,
    club: RangeClubState,
    isLandscape: Boolean,
    onSelectClub: (GolfClub) -> Unit,
) {
    val metrics = detailMetrics(shot)
    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(RangeTestTags.METRICS_DETAIL),
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            ClubMetric(club, onSelectClub, Modifier.weight(CLUB_CELL_WEIGHT))
            for (metric in metrics) DetailMetric(metric, Modifier.weight(1f))
        }
    } else {
        // Two columns: the club selector first, then the seven metrics.
        val cells: List<(@Composable (Modifier) -> Unit)> =
            listOf<@Composable (Modifier) -> Unit>({ ClubMetric(club, onSelectClub, it) }) +
                metrics.map { metric -> { modifier: Modifier -> DetailMetric(metric, modifier) } }
        Column(
            modifier = Modifier.fillMaxWidth().testTag(RangeTestTags.METRICS_DETAIL),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            for (row in cells.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                    for (cell in row) cell(Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ClubMetric(
    club: RangeClubState,
    onSelectClub: (GolfClub) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.background(CellBackground, CellShape).padding(OfSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OfText(
            text = "NEXT CLUB",
            role = OfTextRole.Eyebrow,
            color = OfColorTokens.CreamDim,
            maxLines = 1,
            modifier = Modifier.padding(start = OfSpacing.Sm),
        )
        OfDropdownMenu(
            label = "",
            selected = club.selected.displayName,
            options = GolfClub.entries.map { it.displayName },
            onSelect = { name -> GolfClub.entries.firstOrNull { it.displayName == name }?.let(onSelectClub) },
            enabled = club.selectionEnabled,
            isBusy = club.isChanging,
            modifier = Modifier.testTag(RangeTestTags.CLUB_SELECTOR),
        )
    }
}

@Composable
private fun DetailMetric(
    metric: RangeMetricValue,
    modifier: Modifier,
) {
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
private val CellShape = RoundedCornerShape(12.dp)
private val CellBackground = Color.Black.copy(alpha = 0.55f)
