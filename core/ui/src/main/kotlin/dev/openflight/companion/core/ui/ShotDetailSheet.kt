// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfReduceMotion
import dev.openflight.companion.core.insights.ShotDetailBuilder
import dev.openflight.companion.core.insights.ShotDetailInput
import dev.openflight.companion.core.insights.ShotDetailMetric
import dev.openflight.companion.core.insights.ShotDetailState
import dev.openflight.companion.core.insights.ShotDetailTestTags
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.Conditions

/**
 * One shot in depth, over the screen it was tapped on (the session list, a stored session, a club's
 * recent shots): its mini ball flight and shape, then every metric. [onViewOnRange] adds a "Range"
 * action that opens the full driving range on this shot.
 */
@Composable
fun ShotDetailSheet(
    state: ShotDetailState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onViewOnRange: (() -> Unit)? = null,
) {
    OfBottomSheet(onDismiss = onDismiss, modifier = modifier.testTag(ShotDetailTestTags.SHEET)) {
        ShotDetailContent(state = state, onClose = onDismiss, onViewOnRange = onViewOnRange)
    }
}

/** The sheet's content, stateless, for previews and tests. */
@Composable
fun ShotDetailContent(
    state: ShotDetailState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onViewOnRange: (() -> Unit)? = null,
    reduceMotion: Boolean = rememberOfReduceMotion(),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                OfText(
                    text = state.title,
                    role = OfTextRole.Title,
                    maxLines = 2,
                    modifier = Modifier.testTag(ShotDetailTestTags.TITLE).semantics { heading() },
                )
                OfText(text = state.subtitle, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
            }
            if (onViewOnRange != null && state.flight != null) {
                OfTextButton(
                    text = "Range",
                    onClick = onViewOnRange,
                    modifier = Modifier.testTag(ShotDetailTestTags.VIEW_ON_RANGE),
                )
            }
            OfTextButton(text = "Close", onClick = onClose, modifier = Modifier.testTag(ShotDetailTestTags.CLOSE))
        }
        state.shapeLabel?.let { label ->
            OfStatusChip(
                label = label,
                tone = StatusTone.Neutral,
                detail = state.shapeDescription,
                modifier = Modifier.testTag(ShotDetailTestTags.SHAPE),
            )
        }
        state.flight?.let { flight ->
            ShotFlightView(
                flight = flight,
                contentDescription =
                    listOfNotNull(
                        state.shapeLabel,
                        state.shapeDescription,
                    ).joinToString(". ").ifEmpty { "Ball flight" },
                reduceMotion = reduceMotion,
            )
        }
        MetricsGrid(state.metrics)
        if (state.notes.isNotEmpty()) {
            Column(
                modifier = Modifier.testTag(ShotDetailTestTags.NOTES),
                verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
            ) {
                state.notes.forEach { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim) }
            }
        }
        Spacer(Modifier.height(OfSpacing.Sm))
    }
}

/** The metrics in rows of three (four on a wide sheet, e.g. a tablet). */
@Composable
private fun MetricsGrid(metrics: List<ShotDetailMetric>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= WIDE_SHEET) WIDE_COLUMNS else COLUMNS
        Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
            metrics.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
                    row.forEach { MetricCell(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(
    metric: ShotDetailMetric,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .testTag(ShotDetailTestTags.metric(metric.label))
                .semantics(mergeDescendants = true) {},
    ) {
        OfText(text = metric.label.uppercase(), role = OfTextRole.Label, color = OfColorTokens.CreamDim, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
            OfText(text = metric.value, role = OfTextRole.TitleSmall, maxLines = 1)
            if (metric.unit.isNotEmpty()) {
                OfText(text = metric.unit, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim, maxLines = 1)
            }
        }
        if (metric.estimated) OfText(text = "est.", role = OfTextRole.Label, color = OfColorTokens.CreamMuted)
    }
}

private val WIDE_SHEET = 560.dp
private const val COLUMNS = 3
private const val WIDE_COLUMNS = 4

/** A push fade 7-iron, for previews. */
internal fun previewShotDetail(): ShotDetailState =
    ShotDetailBuilder().build(
        ShotDetailInput(
            id = "preview",
            shotNumber = 12,
            club = "7-iron",
            timestamp = "2026-07-29T19:42:10.123456",
            ballSpeedMph = 120.0,
            carryYards = 165.0,
            clubSpeedMph = 88.0,
            smashFactor = 1.36,
            launchAngleVertical = 16.2,
            launchAngleHorizontal = 2.6,
            spinRpm = 7000.0,
            spinAxisDeg = 6.3,
            spinSource = "measured",
            clubPathDeg = -1.4,
        ),
        Conditions.ISA,
        null,
        UnitSystem.IMPERIAL,
    )

@Preview
@Composable
private fun ShotDetailContentPreview() {
    OfTheme {
        ShotDetailContent(previewShotDetail(), onClose = {}, onViewOnRange = {}, reduceMotion = true)
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ShotDetailContentTabletPreview() {
    OfTheme {
        ShotDetailContent(previewShotDetail(), onClose = {}, reduceMotion = true)
    }
}
