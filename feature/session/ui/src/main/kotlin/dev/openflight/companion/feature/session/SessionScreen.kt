// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfirmDialog
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfMessageHostState
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * The session/stats screen (plans R5b/R6c), ported from the web UI's `StatsView.tsx` (club tabs and
 * stats) and `ShotList.tsx` (swipe-to-delete rows, clear, CSV export). Stateless apart from the
 * clear-confirmation dialog's visibility: everything else comes from [uiState] and goes out through
 * [onEvent] or [onBack].
 */
@Composable
fun SessionScreen(
    uiState: SessionUiState,
    onEvent: (SessionEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    messages: OfMessageHostState? = null,
) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    OfScaffold(
        modifier = modifier,
        messages = messages,
        topBar = {
            OfTopBar(
                title = "Session",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(SessionTestTags.DONE),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg),
        ) {
            item(key = "header") { SourceRow(uiState, onEvent) }
            if (!uiState.hasShots) {
                item(key = "empty") { EmptySession() }
            } else {
                item(key = "tabs") { ClubTabs(uiState, onEvent) }
                item(key = "stats") { StatsCard(uiState) }
                item(key = "actions") {
                    ActionsRow(onExport = { onEvent(SessionEvent.ExportCsv) }, onClear = { confirmingClear = true })
                }
                item(key = "shotsHeader") {
                    OfText(text = "SHOTS · swipe left to delete", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
                }
                items(uiState.shots, key = { it.id }) { shot ->
                    SessionShotRowItem(
                        shot = shot,
                        units = uiState.units,
                        onDelete = { onEvent(SessionEvent.DeleteShot(shot.id)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    if (confirmingClear) {
        OfConfirmDialog(
            title = "Clear session?",
            message = clearMessage(uiState.source),
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                confirmingClear = false
                onEvent(SessionEvent.ClearHistory)
            },
            onDismiss = { confirmingClear = false },
            confirmTag = SessionTestTags.CLEAR_CONFIRM,
            dismissTag = SessionTestTags.CLEAR_CANCEL,
        )
    }
}

private fun clearMessage(source: SessionSource): String =
    when (source) {
        SessionSource.PI -> "Every shot is removed from this phone and from the Pi's session."
        SessionSource.LOCAL -> "Every shot is removed from this phone."
    }

/** The source badge ("Pi session" or "This phone") and, on a `--mock` Pi, "Simulate Shot". */
@Composable
private fun SourceRow(
    uiState: SessionUiState,
    onEvent: (SessionEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, detail) =
                when (uiState.source) {
                    SessionSource.PI -> "Pi session" to "Shots and stats from the OpenFlight Pi"
                    SessionSource.LOCAL -> "This phone" to "Shots this phone received"
                }
            OfPill(
                label = label,
                detail = detail,
                tone = if (uiState.source == SessionSource.PI) StatusTone.Positive else StatusTone.Neutral,
                modifier = Modifier.weight(1f).testTag(SessionTestTags.SOURCE),
            )
        }
        if (uiState.showSimulateShot) {
            val reason = uiState.simulateAvailability.disabledReason
            OfButton(
                text = uiState.simulateLabel,
                onClick = { onEvent(SessionEvent.SimulateShot) },
                enabled = reason == null,
                modifier = Modifier.fillMaxWidth().testTag(SessionTestTags.SIMULATE),
            )
            reason?.let { OfDisabledReason(it) }
        }
    }
}

@Composable
private fun EmptySession() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag(SessionTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm, Alignment.CenterVertically),
    ) {
        OfText(text = "No shots recorded yet", role = OfTextRole.Title, textAlign = TextAlign.Center)
        OfText(
            text = "Hit a ball and it shows up here with per-club stats.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** "All" plus one tab per club with its count (`StatsView.tsx`'s club filter). */
@Composable
private fun ClubTabs(
    uiState: SessionUiState,
    onEvent: (SessionEvent) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        OfChip(
            label = "All",
            count = uiState.allCount,
            selected = uiState.selectedClub == null,
            onClick = { onEvent(SessionEvent.SelectClub(null)) },
            modifier = Modifier.testTag(SessionTestTags.ALL_TAB),
        )
        uiState.clubChips.forEach { chip ->
            OfChip(
                label = clubLabel(chip.club),
                count = chip.count,
                selected = uiState.selectedClub == chip.club,
                onClick = { onEvent(SessionEvent.SelectClub(chip.club)) },
                modifier = Modifier.testTag(SessionTestTags.tab(chip.club)),
            )
        }
    }
}

@Composable
private fun ActionsRow(
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfOutlinedButton(
            text = "Export CSV",
            onClick = onExport,
            modifier = Modifier.weight(1f).testTag(SessionTestTags.EXPORT),
        )
        OfOutlinedButton(
            text = "Clear",
            onClick = onClear,
            modifier = Modifier.weight(1f).testTag(SessionTestTags.CLEAR),
        )
    }
}
