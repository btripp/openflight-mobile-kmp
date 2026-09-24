// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.formatSpeed
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/**
 * The swing-speed training screen (plan R6c), ported from the web UI's swing-speed `ShotDisplay.tsx`
 * view (Last/Best/Average) and `TrainingImplementPicker.tsx` (implements grouped by training
 * system). Wi-Fi only: without the Pi's live session the picker is disabled with the VM's reason.
 * Stateless: everything comes from [uiState] and goes out through [onEvent] or [onBack].
 */
@Composable
fun TrainingScreen(
    uiState: TrainingUiState,
    onEvent: (TrainingEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = "Swing Training",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(TrainingTestTags.DONE),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg),
        ) {
            StatusCard(uiState, onEvent)
            uiState.error?.let { ErrorBanner(it, onDismiss = { onEvent(TrainingEvent.DismissError) }) }
            SpeedCard(uiState.stats, uiState.units, uiState.lastRep)
            ImplementPicker(uiState, onEvent)
        }
    }
}

/** Availability, the Pi's trigger mode, the player and (on a `--mock` Pi) "Simulate Swing". */
@Composable
private fun StatusCard(
    uiState: TrainingUiState,
    onEvent: (TrainingEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Sm) {
        OfText(text = "SESSION", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        OfText(text = uiState.playerName, role = OfTextRole.Title)
        OfText(
            text = modeText(uiState),
            role = OfTextRole.BodySmall,
            color = if (uiState.isSwingSpeedMode) OfColorTokens.Success else OfColorTokens.CreamDim,
            modifier = Modifier.testTag(TrainingTestTags.MODE),
        )
        uiState.availability.disabledReason?.let {
            OfDisabledReason(reason = it, modifier = Modifier.testTag(TrainingTestTags.AVAILABILITY))
        }
        if (uiState.showSimulateSwing) {
            OfButton(
                text = "Simulate Swing",
                onClick = { onEvent(TrainingEvent.SimulateSwing) },
                enabled = uiState.availability.isAvailable,
                modifier = Modifier.fillMaxWidth().testTag(TrainingTestTags.SIMULATE),
            )
        }
    }
}

private fun modeText(uiState: TrainingUiState): String =
    when {
        uiState.triggerMode == null -> "Waiting for the Pi's trigger mode"
        uiState.isSwingSpeedMode -> "Swing-speed mode: every swing is a rep"
        else -> "Trigger mode \"${uiState.triggerMode}\": start the Pi in swing-speed mode for reps"
    }

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    OfText(
        text = "⚠ $message",
        role = OfTextRole.BodySmall,
        color = OfColorTokens.Danger,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OfColorTokens.Danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
                .padding(OfSpacing.Md)
                .testTag(TrainingTestTags.ERROR),
    )
}

/** Last, Best and Average swing speed for this player and implement, plus the rep count. */
@Composable
private fun SpeedCard(
    stats: SwingSpeedStats,
    units: UnitSystem,
    lastRep: SwingRep?,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "SWING SPEED", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        val unit = speedUnitLabel(units)
        val hasSwings = stats.count > 0
        Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
            SpeedTile(
                "Last",
                speed(stats.lastSpeedMph, units, hasSwings),
                unit,
                TrainingTestTags.LAST,
                Modifier.weight(1f),
            )
            SpeedTile(
                "Best",
                speed(stats.bestSpeedMph, units, hasSwings),
                unit,
                TrainingTestTags.BEST,
                Modifier.weight(1f),
            )
            SpeedTile(
                "Average",
                speed(stats.avgSpeedMph, units, hasSwings),
                unit,
                TrainingTestTags.AVERAGE,
                Modifier.weight(1f),
            )
        }
        OfText(
            text = if (hasSwings) "${stats.count} swings (this player and implement)" else "No swings yet",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            modifier = Modifier.testTag(TrainingTestTags.COUNT),
        )
        lastRep?.let { rep ->
            OfText(text = repDetail(rep, units), role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        }
    }
}

private fun speed(
    mph: Double,
    units: UnitSystem,
    hasSwings: Boolean,
): String =
    if (hasSwings) ShotMetricFormatter.number(convertSpeedFromMph(mph, units), 1) else ShotMetricFormatter.MISSING

/** The newest rep's details (`ShotDisplay.tsx`'s swing-speed subtexts). */
private fun repDetail(
    rep: SwingRep,
    units: UnitSystem,
): String =
    listOfNotNull(
        "Latest: ${formatSpeed(rep.speedMph, units)}",
        rep.implementLabel,
        rep.readingCount?.let { "$it radar readings" },
        rep.triggerSpeedMph?.let { "${formatSpeed(it, units)} trigger" },
    ).joinToString(" · ")

@Composable
private fun SpeedTile(
    label: String,
    value: String,
    unit: String,
    tag: String,
    modifier: Modifier,
) {
    Column(
        modifier =
            modifier
                .background(OfColorTokens.BgElevated, RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) { contentDescription = "$label, $value $unit" }
                .testTag(tag)
                .padding(OfSpacing.Md),
    ) {
        OfText(text = label.uppercase(), role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
        OfText(text = value, role = OfTextRole.Headline, maxLines = 1)
        OfText(text = unit, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
    }
}

/** `TrainingImplementPicker.tsx`, flattened: every group's implements as selectable chips. */
@Composable
private fun ImplementPicker(
    uiState: TrainingUiState,
    onEvent: (TrainingEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "IMPLEMENT", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        OfText(text = uiState.selectedImplement.label, role = OfTextRole.Title)
        uiState.availability.disabledReason?.let { OfDisabledReason(it) }
        uiState.implementGroups.forEach { group ->
            Column(
                modifier = Modifier.testTag(TrainingTestTags.group(group.name)),
                verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            ) {
                OfText(text = group.name, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                ) {
                    group.options.forEach { option ->
                        OfChip(
                            label = option.label,
                            selected = option.id == uiState.selectedImplement.id,
                            enabled = uiState.availability.isAvailable,
                            onClick = { onEvent(TrainingEvent.SelectImplement(option.id)) },
                            modifier = Modifier.testTag(TrainingTestTags.implement(option.id)),
                        )
                    }
                }
            }
        }
    }
}
