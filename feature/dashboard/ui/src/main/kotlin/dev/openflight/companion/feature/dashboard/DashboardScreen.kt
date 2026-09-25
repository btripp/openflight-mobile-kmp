// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import dev.openflight.companion.core.designsystem.OfBottomBar
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
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.SpinSource
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * The dashboard (ContentView.swift): header with the Range entry, the connection card, and either
 * the latest shot with previous shots or the "Waiting for a shot" state. Stateless: everything comes
 * from [uiState] and every interaction goes out through [onEvent] or a navigation callback.
 *
 * Plans R5b/R6c add the bottom bar to Session, Training, Camera and Settings ([navigation]), the
 * per-club chips, units, the Pi's confidence badges/carry range/player, and the gold shot-flash,
 * which replays whenever [shotFlashes] changes (the route bumps it on `DashboardEffect.NewShot`).
 */
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenRange: () -> Unit,
    modifier: Modifier = Modifier,
    navigation: DashboardNavigation = DashboardNavigation(),
    shotFlashes: Int = 0,
) {
    OfScaffold(
        modifier = modifier,
        bottomBar = { DashboardBottomBar(navigation) },
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
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Xl),
        ) {
            ConnectionCard(uiState.connection, onEvent, onOpenCalibration)
            uiState.processing?.let { ProcessingNotice(it) }
            when (uiState) {
                is DashboardUiState.Waiting -> {
                    EmptyState()
                }

                is DashboardUiState.Live -> {
                    Box {
                        ShotCard(uiState.latest, uiState.units, uiState.latestEnrichment)
                        ShotFlash(trigger = shotFlashes, modifier = Modifier.matchParentSize())
                    }
                    if (uiState.clubChips.isNotEmpty()) ClubChipsCard(uiState.clubChips)
                    if (uiState.previous.isNotEmpty()) ShotHistoryCard(uiState.previous, uiState.units)
                }
            }
        }
    }
}

/** Where the dashboard's bottom bar goes: the Wi-Fi-parity screens (plans R5b/R6c). */
data class DashboardNavigation(
    val onOpenSession: () -> Unit = {},
    val onOpenTraining: () -> Unit = {},
    val onOpenCamera: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
private fun DashboardBottomBar(navigation: DashboardNavigation) {
    OfBottomBar {
        OfTextButton(
            text = "Session",
            onClick = navigation.onOpenSession,
            modifier = Modifier.testTag(DashboardUiTags.SESSION),
        )
        OfTextButton(
            text = "Training",
            onClick = navigation.onOpenTraining,
            modifier = Modifier.testTag(DashboardUiTags.TRAINING),
        )
        OfTextButton(
            text = "Camera",
            onClick = navigation.onOpenCamera,
            modifier = Modifier.testTag(DashboardUiTags.CAMERA),
        )
        OfTextButton(
            text = "Settings",
            onClick = navigation.onOpenSettings,
            modifier = Modifier.testTag(DashboardUiTags.SETTINGS),
        )
    }
}

@Composable
private fun ConnectionCard(
    panel: ConnectionPanelState,
    onEvent: (DashboardEvent) -> Unit,
    onOpenCalibration: () -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfSegmentedPicker(
            options = TransportType.entries.map { it.label },
            selected = panel.transport.label,
            onSelect = { label ->
                val transport = TransportType.entries.first { it.label == label }
                if (transport != panel.transport) onEvent(DashboardEvent.TransportChanged(transport))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        StatusRow(panel, onRetry = { onEvent(DashboardEvent.Retry) })
        panel.visibleProblem?.let { ConnectionProblemNotice(it) }
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
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).testTag(DashboardTestTags.EMPTY_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm, Alignment.CenterVertically),
    ) {
        OfText(text = "Waiting for a shot", role = OfTextRole.Title, textAlign = TextAlign.Center)
        OfText(
            text = "Connect to your OpenFlight Pi, then hit a ball.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** ContentView.swift `statusColor`: green connected, orange working, red failed, gray idle. */
private fun ConnectionState.tone(): StatusTone =
    when (this) {
        ConnectionState.Connected -> StatusTone.Positive
        ConnectionState.Scanning, ConnectionState.Connecting, ConnectionState.Discovering -> StatusTone.InProgress
        is ConnectionState.Error, is ConnectionState.Unavailable -> StatusTone.Negative
        ConnectionState.Idle -> StatusTone.Neutral
    }

@Preview
@Composable
private fun DashboardLivePreview() {
    val shot =
        ShotEvent(
            schemaVersion = 1,
            eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
            timestamp = "2026-07-29T19:42:10",
            club = "driver",
            ballSpeedMph = 151.4,
            clubSpeedMph = 103.2,
            smashFactor = 1.47,
            estimatedCarryYards = 264.0,
            launchAngleVertical = 12.6,
            launchAngleHorizontal = -1.3,
            spinRpm = 2380.0,
            clubPathDeg = 2.1,
            spinAxisDeg = -3.4,
        )
    OfTheme {
        DashboardScreen(
            uiState =
                DashboardUiState.Live(
                    connection =
                        ConnectionPanelState(
                            transport = TransportType.WIFI,
                            state = ConnectionState.Connected,
                        ),
                    latest = shot,
                    previous = listOf(shot.copy(eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E2", club = "7-iron")),
                    clubChips = listOf(ClubChip("driver", 1), ClubChip("7-iron", 1)),
                    enrichments =
                        mapOf(
                            shot.eventId to
                                ShotEnrichment(
                                    launchAngleConfidence = ConfidenceLevel.HIGH,
                                    angleSource = "radar",
                                    spinQuality = ConfidenceLevel.MEDIUM,
                                    spinSource = SpinSource.ESTIMATED,
                                    carryRangeLowYards = 251.0,
                                    carryRangeHighYards = 277.0,
                                    carrySpinAdjustedYards = null,
                                    profileName = "Alex",
                                ),
                        ),
                ),
            onEvent = {},
            onOpenCalibration = {},
            onOpenRange = {},
        )
    }
}
