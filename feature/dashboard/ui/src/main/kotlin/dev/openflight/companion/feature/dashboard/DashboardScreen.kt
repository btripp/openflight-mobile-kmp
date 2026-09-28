// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfListDetailPane
import dev.openflight.companion.core.designsystem.OfMenuItem
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfMetricPrimary
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfOverflowMenu
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub

/**
 * The dashboard (ContentView.swift): header with the Range entry, the connection card, and either
 * the latest shot with previous shots or the "Waiting for a shot" state. Stateless: everything comes
 * from [uiState] and every interaction goes out through [onEvent] or a navigation callback.
 *
 * Plans R5b/R6c add the per-club chips, units, the Pi's confidence badges/carry range/player, and the gold shot-flash,
 * which replays whenever [shotFlashes] changes (the route bumps it on `DashboardEffect.NewShot`).
 * Sessions, Bag and Settings are reached from the app shell's bottom bar or rail (plans F1a/F1d).
 * Plan F1d: this is the Practice destination, and its top-bar overflow pushes swing-speed training
 * ([onOpenTraining]; no menu when it's null).
 *
 * @param windowClass injectable for tests and previews; defaults to [rememberOfWindowClass]. On an
 *   [OfWindowClass.EXPANDED] window the live metrics and the connection/club card sit side by side
 *   in two independently-scrolling columns (plan F1b) instead of stacking in one.
 * @param onViewOnRange plan F8d: the latest shot's "View on range", by its event id; `null` hides it.
 */
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenRange: () -> Unit,
    modifier: Modifier = Modifier,
    shotFlashes: Int = 0,
    windowClass: OfWindowClass = rememberOfWindowClass(),
    onOpenTraining: (() -> Unit)? = null,
    onViewOnRange: ((eventId: String) -> Unit)? = null,
) {
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = "Launch Monitor",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Range",
                        onClick = onOpenRange,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(DashboardTestTags.RANGE),
                    )
                    // Plan F1d: Training is a pushed route now, reached from Practice's overflow.
                    if (onOpenTraining != null) {
                        OfOverflowMenu(
                            items =
                                listOf(
                                    OfMenuItem(
                                        label = "Speed training",
                                        onClick = onOpenTraining,
                                        testTag = DashboardTestTags.OPEN_TRAINING,
                                    ),
                                ),
                            modifier = Modifier.testTag(DashboardTestTags.MORE),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val expanded = windowClass == OfWindowClass.EXPANDED
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Above the long connection card, so it's on screen while swinging (plan R8f). Full
            // width in both layouts: it's a transient banner, not part of either column.
            uiState.processing?.let {
                Box(modifier = Modifier.padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm)) {
                    ProcessingNotice(it)
                }
            }
            // Issue #48: the Pi's battery, in the same full-width banner slot: it matters on either layout.
            uiState.batteryWarning?.let {
                Box(modifier = Modifier.padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm)) {
                    BatteryWarningNotice(it)
                }
            }
            // hasSelection is always false: there's no selection here, only whether the window is
            // wide enough for two panes. On COMPACT/MEDIUM that's a single LIST pane holding
            // everything stacked, same as before F1b; DETAIL is only ever composed on EXPANDED.
            OfListDetailPane(
                hasSelection = false,
                windowClass = windowClass,
                modifier = Modifier.fillMaxSize(),
                list = {
                    // On COMPACT/MEDIUM this pane holds everything (no detail pane is ever shown),
                    // so it only gets the "metrics column" tag once it's actually metrics-only.
                    ColumnContent(
                        modifier = if (expanded) Modifier.testTag(DashboardTestTags.METRICS_COLUMN) else Modifier,
                    ) {
                        if (expanded) {
                            MetricsContent(uiState, shotFlashes, onViewOnRange)
                        } else {
                            ConnectionCard(uiState.connection, onEvent, onOpenCalibration)
                            MetricsContent(uiState, shotFlashes, onViewOnRange)
                        }
                    }
                },
                detail = {
                    ColumnContent(modifier = Modifier.testTag(DashboardTestTags.CONNECTION_COLUMN)) {
                        ConnectionCard(uiState.connection, onEvent, onOpenCalibration)
                    }
                },
            )
        }
    }
}

/** A scrollable, padded column: the shared shell for the dashboard's list and detail panes. */
@Composable
private fun ColumnContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xl),
        content = content,
    )
}

/** The live metrics: the latest shot (or the empty state), the club chips and the shot history. */
@Composable
private fun MetricsContent(
    uiState: DashboardUiState,
    shotFlashes: Int,
    onViewOnRange: ((eventId: String) -> Unit)?,
) {
    when (uiState) {
        is DashboardUiState.Waiting -> {
            EmptyState(demo = uiState.connection.demo)
        }

        is DashboardUiState.Live -> {
            Box {
                ShotCard(
                    uiState.latest,
                    uiState.units,
                    uiState.latestEnrichment,
                    onViewOnRange = onViewOnRange?.let { view -> { view(uiState.latest.eventId) } },
                    demo = uiState.connection.demo,
                )
                ShotFlash(trigger = shotFlashes, modifier = Modifier.matchParentSize())
            }
            if (uiState.clubChips.isNotEmpty()) ClubChipsCard(uiState.clubChips)
            if (uiState.previous.isNotEmpty()) ShotHistoryCard(uiState.previous, uiState.units)
        }
    }
}

@Composable
private fun ConnectionCard(
    panel: ConnectionPanelState,
    onEvent: (DashboardEvent) -> Unit,
    onOpenCalibration: () -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        // Plan F14: Demo mode's pretend Pi needs no transport or address.
        if (!panel.demo) {
            OfSegmentedPicker(
                options = TransportType.entries.map { it.label },
                selected = panel.transport.label,
                onSelect = { label ->
                    val transport = TransportType.entries.first { it.label == label }
                    if (transport != panel.transport) onEvent(DashboardEvent.TransportChanged(transport))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        StatusRow(panel, onRetry = { onEvent(DashboardEvent.Retry) })
        if (panel.demo) DemoControls(onEvent)
        panel.visibleProblem?.let { ConnectionProblemNotice(it) }
        if (panel.showTryDemo) TryDemo(onEvent)
        if (panel.showHostField) {
            OfTextField(
                value = panel.hostText,
                onValueChange = { onEvent(DashboardEvent.HostEdited(it)) },
                placeholder = "raspberrypi.local:8080",
                monospace = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                onSubmit = { onEvent(DashboardEvent.HostSubmitted) },
                modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.HOST_FIELD),
            )
            HostHints(panel.hostHints, onEvent)
        }
        if (panel.localNetworkDenied) LocalNetworkDenied()
        if (panel.showClubConfirmation) ClubConfirmationPrompt(panel.club, onEvent)
        ClubSelector(panel, onEvent)
        ProfileSelector(panel.profile, onEvent)
        OfOutlinedButton(
            text = "Calibrate TI Radar",
            onClick = onOpenCalibration,
            modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.CALIBRATE_RADAR),
        )
        panel.helpLink?.let { HelpLink(it) }
    }
}

/** Plan F14: what Demo mode is, "Hit a shot" and the way back to a real Pi. */
@Composable
private fun DemoControls(onEvent: (DashboardEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfText(text = ConnectionPanelState.DEMO_NOTE, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
            OfButton(
                text = ConnectionPanelState.HIT_SHOT_LABEL,
                onClick = { onEvent(DashboardEvent.HitDemoShot) },
                modifier = Modifier.weight(1f).testTag(DashboardTestTags.HIT_SHOT),
            )
            OfOutlinedButton(
                text = ConnectionPanelState.EXIT_DEMO_LABEL,
                onClick = { onEvent(DashboardEvent.ExitDemo) },
                modifier = Modifier.weight(1f).testTag(DashboardTestTags.EXIT_DEMO),
            )
        }
    }
}

/** Plan F14: the entry to Demo mode, while no Pi is connected. */
@Composable
private fun TryDemo(onEvent: (DashboardEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
        OfOutlinedButton(
            text = ConnectionPanelState.TRY_DEMO_LABEL,
            onClick = { onEvent(DashboardEvent.TryDemo) },
            modifier = Modifier.fillMaxWidth().testTag(DashboardTestTags.TRY_DEMO),
        )
        OfText(text = ConnectionPanelState.TRY_DEMO_NOTE, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
    }
}

@Composable
private fun StatusRow(
    panel: ConnectionPanelState,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OfStatusChip(
            label = panel.statusTitle,
            tone = panel.state.tone(),
            detail = panel.state.description,
            modifier = Modifier.weight(1f),
        )
        when {
            panel.showRetry -> {
                OfOutlinedButton(
                    text = "Retry",
                    onClick = onRetry,
                    modifier = Modifier.testTag(DashboardTestTags.RETRY),
                )
            }

            panel.showProgress -> {
                OfSpinner(modifier = Modifier.size(24.dp).testTag(DashboardTestTags.PROGRESS))
            }
        }
    }
}

@Composable
private fun ClubSelector(
    panel: ConnectionPanelState,
    onEvent: (DashboardEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfText(text = "CLUB FOR NEXT SHOT", role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
        OfDropdownMenu(
            label = "",
            selected = panel.club.displayName,
            options = GolfClub.entries.map { it.displayName },
            onSelect = { name ->
                GolfClub.entries.firstOrNull { it.displayName == name }?.let {
                    onEvent(
                        DashboardEvent.ClubSelected(it),
                    )
                }
            },
            enabled = panel.clubMenuEnabled,
            isBusy = panel.isChangingClub,
            modifier = Modifier.testTag(DashboardTestTags.CLUB_SELECTOR),
        )
        panel.clubError?.let { error ->
            OfText(
                text = "⚠ $error",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Danger,
                modifier =
                    Modifier
                        .clickable { onEvent(DashboardEvent.DismissError) }
                        .testTag(DashboardTestTags.CLUB_ERROR),
            )
        }
    }
}

@Composable
private fun EmptyState(demo: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).testTag(DashboardTestTags.EMPTY_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm, Alignment.CenterVertically),
    ) {
        OfText(text = "Waiting for a shot", role = OfTextRole.Title, textAlign = TextAlign.Center)
        OfText(
            text = if (demo) ConnectionPanelState.DEMO_EMPTY_NOTE else CONNECT_NOTE,
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
        )
    }
}

private const val CONNECT_NOTE = "Connect to your OpenFlight Pi, then hit a ball."

/** ContentView.swift `statusColor`: green connected, orange working, red failed, gray idle. */
private fun ConnectionState.tone(): StatusTone =
    when (this) {
        ConnectionState.Connected -> StatusTone.Positive
        ConnectionState.Scanning, ConnectionState.Connecting, ConnectionState.Discovering -> StatusTone.InProgress
        is ConnectionState.Error, is ConnectionState.Unavailable -> StatusTone.Negative
        ConnectionState.Idle -> StatusTone.Neutral
    }
