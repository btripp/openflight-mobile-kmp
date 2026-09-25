// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfirmDialog
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfMessageHostState
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiLinkState

/**
 * The settings screen (plan R6c): the phone's unit preference and connection info, then the Pi's
 * Wi-Fi-only controls from the web UI's header and Debug tab (player, simulator status, radar
 * tuning and diagnostics, debug logging, cloud upload) and the Pi shutdown behind a confirmation.
 * Every Wi-Fi-only control is disabled with the VM's reason. Stateless apart from the unsent
 * player-name draft.
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    messages: OfMessageHostState? = null,
) {
    OfScaffold(
        modifier = modifier,
        messages = messages,
        topBar = {
            OfTopBar(
                title = "Settings",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(SettingsTestTags.DONE),
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
            UnitsCard(uiState.units, onEvent)
            ConnectionCard(uiState)
            ProfileCard(uiState.profile)
            SimulatorsCard(uiState.simulators, uiState.profile.availability.disabledReason)
            RadarCard(uiState.radar, onEvent)
            DebugCard(uiState.debug, onEvent)
            CloudCard(uiState.cloud, onEvent)
            ShutdownCard(uiState.shutdown, onEvent)
        }
    }
    if (uiState.shutdown.confirmationRequired) {
        OfConfirmDialog(
            title = ShutdownSettings.CONFIRMATION_TEXT,
            message = "The Pi powers off. You'll need to switch it back on by hand to use OpenFlight again.",
            confirmLabel = "Shut Down",
            destructive = true,
            onConfirm = { onEvent(SettingsEvent.ConfirmShutdown) },
            onDismiss = { onEvent(SettingsEvent.CancelShutdown) },
            confirmTag = SettingsTestTags.SHUTDOWN_CONFIRM,
            dismissTag = SettingsTestTags.SHUTDOWN_CANCEL,
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    OfText(text = text, role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
}

@Composable
private fun UnitsCard(
    units: UnitSystem,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("UNITS")
        val labels = mapOf(UnitSystem.IMPERIAL to "Imperial (mph, yds)", UnitSystem.METRIC to "Metric (km/h, m)")
        OfSegmentedPicker(
            options = labels.values.toList(),
            selected = labels.getValue(units),
            onSelect = { label ->
                val picked = labels.entries.first { it.value == label }.key
                if (picked != units) onEvent(SettingsEvent.SetUnits(picked))
            },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.UNITS),
        )
    }
}

/** The transport, the shot stream's state and the Pi's Socket.IO link. */
@Composable
private fun ConnectionCard(uiState: SettingsUiState) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CONNECTION), contentSpacing = OfSpacing.Md) {
        SectionTitle("CONNECTION")
        InfoRow("Transport", if (uiState.transport == TransportType.WIFI) "Wi-Fi" else "Bluetooth")
        if (uiState.transport == TransportType.WIFI) InfoRow("Host", uiState.host)
        InfoRow("Shot stream", uiState.connectionState.description)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfText(
                text = "Live session",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.weight(1f),
            )
            OfPill(label = uiState.linkDescription, tone = uiState.linkState.tone())
        }
        if (uiState.mockMode) {
            OfText(text = "The Pi runs in mock mode", role = OfTextRole.BodySmall, color = OfColorTokens.Warning)
        }
    }
}

@Composable
internal fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The label keeps its width; a long value wraps within the rest of the row.
        OfText(text = label, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        OfText(
            text = value,
            role = OfTextRole.TitleSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = OfSpacing.Md),
        )
    }
}

private fun PiLinkState.tone(): StatusTone =
    when (this) {
        PiLinkState.Connected -> StatusTone.Positive
        PiLinkState.Connecting, is PiLinkState.Reconnecting -> StatusTone.InProgress
        PiLinkState.Idle, PiLinkState.WifiOnly -> StatusTone.Neutral
        is PiLinkState.Rejected -> StatusTone.Negative
    }

/** The Pi's active profile (read-only until the profile picker, plan R8f). */
@Composable
private fun ProfileCard(profile: ProfileSettings) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("PROFILE")
        InfoRow("Active profile", profile.activeName ?: "—", Modifier.testTag(SettingsTestTags.PROFILE))
        profile.availability.disabledReason?.let { OfDisabledReason(it) }
    }
}

/** `App.tsx`'s power button and "Shut down OpenFlight?" dialog. */
@Composable
private fun ShutdownCard(
    shutdown: ShutdownSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("POWER")
        OfTextButton(
            text = "Shut Down Pi",
            destructive = true,
            enabled = shutdown.shutdown.isAvailable,
            onClick = { onEvent(SettingsEvent.RequestShutdown) },
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SHUTDOWN),
        )
        shutdown.shutdown.disabledReason?.let { OfDisabledReason(it) }
    }
}
