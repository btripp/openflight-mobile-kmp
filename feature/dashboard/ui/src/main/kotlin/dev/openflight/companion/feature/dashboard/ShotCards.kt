// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfidenceDots
import dev.openflight.companion.core.designsystem.OfDivider
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfMetricPrimary
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.metricContentDescription
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter

// The latest-shot and previous-shots cards (ContentView.swift `shotCard`, `shotHistoryCard`),
// plus the web UI's confidence badges, carry range and club chips (plans R5b/R6c).

private const val SPIN_ADJUSTED = "spin-adjusted"

@Composable
internal fun ShotCard(
    shot: ShotEvent,
    units: UnitSystem = UnitSystem.IMPERIAL,
    enrichment: ShotEnrichment? = null,
) {
    OfCard(
        modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.LATEST_SHOT),
        contentSpacing = OfSpacing.Xl,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OfText(text = "LATEST SHOT", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
            OfText(text = shot.displayClub, role = OfTextRole.Title)
            enrichment?.profileName?.let { player ->
                OfText(
                    text = player,
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    modifier = Modifier.testTag(DashboardUiTags.PLAYER),
                )
            }
        }
        PrimaryMetrics(shot, units, enrichment)
        OfDivider()
        val speedUnit = speedUnitLabel(units)
        DetailRow(
            DetailMetric("Club speed", shot.clubSpeedMph?.let { convertSpeedFromMph(it, units) }, 1, speedUnit),
            DetailMetric("Smash", shot.smashFactor, decimals = 2),
        )
        DetailRow(
            DetailMetric(
                "Launch",
                shot.launchAngleVertical,
                decimals = 1,
                unit = "°",
                confidence = enrichment?.launchAngleConfidence,
            ),
            DetailMetric("Direction", shot.launchAngleHorizontal, decimals = 1, unit = "°"),
        )
        DetailRow(
            DetailMetric(
                "Spin",
                shot.spinRpm,
                decimals = 0,
                unit = "rpm",
                confidence = enrichment?.spinQuality,
                subtext = enrichment?.spinSource?.label,
            ),
            DetailMetric("Club path", shot.clubPathDeg, decimals = 1, unit = "°"),
        )
        DetailRow(DetailMetric("Spin axis", shot.spinAxisDeg, decimals = 1, unit = "°"), null)
    }
}

/**
 * Ball speed and carry. Carry follows `ShotDisplay.tsx`: the spin-adjusted carry when the Pi has
 * one (subtext "spin-adjusted"), otherwise the estimate with the carry range as its subtext.
 */
@Composable
private fun PrimaryMetrics(
    shot: ShotEvent,
    units: UnitSystem,
    enrichment: ShotEnrichment?,
) {
    val spinAdjusted = enrichment?.carrySpinAdjustedYards
    val carryYards = spinAdjusted ?: shot.estimatedCarryYards
    val carrySubtext = if (spinAdjusted != null) SPIN_ADJUSTED else enrichment?.carryRangeText(units)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OfMetricPrimary(
            title = "BALL SPEED",
            value = ShotMetricFormatter.number(convertSpeedFromMph(shot.ballSpeedMph, units), decimals = 1),
            unit = speedUnitLabel(units).uppercase(),
            modifier = Modifier.weight(1f),
        )
        OfMetricPrimary(
            title = "CARRY",
            value = ShotMetricFormatter.number(convertDistanceFromYards(carryYards, units), decimals = 0),
            unit = distanceUnitLabel(units).uppercase(),
            subtext = carrySubtext,
            modifier = Modifier.weight(1f).testTag(DashboardUiTags.CARRY),
        )
    }
}

private data class DetailMetric(
    val title: String,
    val value: Double?,
    val decimals: Int,
    val unit: String = "",
    val confidence: ConfidenceLevel? = null,
    val subtext: String? = null,
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OfMetricDetail(
            title = metric.title,
            value = ShotMetricFormatter.number(metric.value, metric.decimals),
            unit = metric.unit,
            modifier = Modifier.testTag(DashboardTestTags.metric(metric.title)),
        )
        metric.subtext?.let {
            OfText(
                text = it,
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.testTag(DashboardUiTags.subtext(metric.title)),
            )
        }
        metric.confidence?.let { level ->
            OfConfidenceDots(
                filledDots = level.filledDots,
                label = level.label,
                maxDots = ConfidenceLevel.MAX_DOTS,
                showDots = level.showsDots,
                modifier = Modifier.testTag(DashboardUiTags.confidence(metric.title)),
            )
        }
    }
}

/** The per-club shot counts (`StatsView.tsx`'s club tabs, display-only here). */
@Composable
internal fun ClubChipsCard(chips: List<ClubChip>) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(DashboardUiTags.CLUB_CHIPS), contentSpacing = OfSpacing.Md) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfText(
                text = "SESSION",
                role = OfTextRole.Eyebrow,
                color = OfColorTokens.Gold,
                modifier = Modifier.weight(1f),
            )
            OfText(text = "${chips.sumOf { it.count }} shots", role = OfTextRole.Label, color = OfColorTokens.CreamDim)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            chips.forEach { chip -> OfChip(label = clubLabel(chip.club), count = chip.count) }
        }
    }
}

/** A wire club value's display name ("7-iron" → "7-Iron"), or the raw value for an unknown club. */
internal fun clubLabel(wire: String): String = GolfClub.fromWireValue(wire)?.displayName ?: wire.ifEmpty { "Unknown" }

@Composable
internal fun ShotHistoryCard(
    previous: List<ShotEvent>,
    units: UnitSystem = UnitSystem.IMPERIAL,
) {
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
            PreviousShotRow(shot, units)
            if (index < previous.lastIndex) OfDivider()
        }
    }
}

@Composable
private fun PreviousShotRow(
    shot: ShotEvent,
    units: UnitSystem,
) {
    val ballSpeed = ShotMetricFormatter.number(convertSpeedFromMph(shot.ballSpeedMph, units), decimals = 1)
    val carry = ShotMetricFormatter.number(convertDistanceFromYards(shot.estimatedCarryYards, units), decimals = 0)
    val speedUnit = speedUnitLabel(units).uppercase()
    val distanceUnit = distanceUnitLabel(units).uppercase()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = OfSpacing.Md)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "${shot.displayClub}, completed shot, " +
                        "${metricContentDescription("Ball speed", ballSpeed, speedUnit)}, " +
                        metricContentDescription("Carry", carry, distanceUnit)
                },
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OfText(text = shot.displayClub, role = OfTextRole.TitleSmall, maxLines = 1)
            OfText(text = "Completed shot", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        }
        HistoryMetric(ballSpeed, speedUnit)
        HistoryMetric(carry, distanceUnit)
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
