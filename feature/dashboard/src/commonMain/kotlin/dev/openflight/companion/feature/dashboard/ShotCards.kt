// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDivider
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfMetricPrimary
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter

// The latest-shot and previous-shots cards (ContentView.swift `shotCard`, `shotHistoryCard`).

@Composable
internal fun ShotCard(shot: ShotEvent) {
    OfCard(
        modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.LATEST_SHOT),
        contentSpacing = OfSpacing.Xl,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OfText(text = "LATEST SHOT", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
            OfText(text = shot.displayClub, role = OfTextRole.Title)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OfMetricPrimary(
                title = "BALL SPEED",
                value = ShotMetricFormatter.number(shot.ballSpeedMph, decimals = 1),
                unit = "MPH",
                modifier = Modifier.weight(1f),
            )
            OfMetricPrimary(
                title = "CARRY",
                value = ShotMetricFormatter.number(shot.estimatedCarryYards, decimals = 0),
                unit = "YDS",
                modifier = Modifier.weight(1f),
            )
        }
        OfDivider()
        DetailRow(
            DetailMetric("Club speed", shot.clubSpeedMph, decimals = 1, unit = "mph"),
            DetailMetric("Smash", shot.smashFactor, decimals = 2),
        )
        DetailRow(
            DetailMetric("Launch", shot.launchAngleVertical, decimals = 1, unit = "°"),
            DetailMetric("Direction", shot.launchAngleHorizontal, decimals = 1, unit = "°"),
        )
        DetailRow(
            DetailMetric("Spin", shot.spinRpm, decimals = 0, unit = "rpm"),
            DetailMetric("Club path", shot.clubPathDeg, decimals = 1, unit = "°"),
        )
        DetailRow(DetailMetric("Spin axis", shot.spinAxisDeg, decimals = 1, unit = "°"), null)
    }
}

private data class DetailMetric(
    val title: String,
    val value: Double?,
    val decimals: Int,
    val unit: String = "",
)

@Composable
private fun DetailRow(
    left: DetailMetric,
    right: DetailMetric?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Xxl)) {
        DetailCell(left, Modifier.weight(1f))
        if (right != null) DetailCell(right, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun DetailCell(
    metric: DetailMetric,
    modifier: Modifier,
) {
    OfMetricDetail(
        title = metric.title,
        value = ShotMetricFormatter.number(metric.value, metric.decimals),
        unit = metric.unit,
        modifier = modifier.testTag(DashboardTestTags.metric(metric.title)),
    )
}

@Composable
internal fun ShotHistoryCard(previous: List<ShotEvent>) {
    OfCard(
        modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.PREVIOUS_SHOTS),
        contentSpacing = 0.dp,
    ) {
        Row(modifier = Modifier.padding(bottom = OfSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            OfText(
                text = "PREVIOUS SHOTS",
                role = OfTextRole.Eyebrow,
                color = OfColorTokens.Gold,
                modifier = Modifier.weight(1f),
            )
            OfText(text = previous.size.toString(), role = OfTextRole.Label, color = OfColorTokens.CreamDim)
        }
        previous.forEachIndexed { index, shot ->
            PreviousShotRow(shot)
            if (index < previous.lastIndex) OfDivider()
        }
    }
}

@Composable
private fun PreviousShotRow(shot: ShotEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = OfSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OfText(text = shot.displayClub, role = OfTextRole.TitleSmall, maxLines = 1)
            OfText(text = "Completed shot", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        }
        HistoryMetric(ShotMetricFormatter.number(shot.ballSpeedMph, decimals = 1), "MPH")
        HistoryMetric(ShotMetricFormatter.number(shot.estimatedCarryYards, decimals = 0), "YDS")
    }
}

@Composable
private fun HistoryMetric(
    value: String,
    unit: String,
) {
    Column(modifier = Modifier.widthIn(min = 62.dp), horizontalAlignment = Alignment.End) {
        OfText(text = value, role = OfTextRole.Title, maxLines = 1)
        OfText(text = unit, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
    }
}
