// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfDisabledReason
import dev.openflight.companion.core.designsystem.OfDivider
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfSlider
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.model.pi.CloudUploadState

// The Pi's Wi-Fi-only panels (plan R6c): SimStatus.tsx, DebugPanel.tsx and the cloud upload.

private const val CLOCK_LENGTH = 8

/** `SimStatus.tsx`: one pill per simulator connector. */
@Composable
internal fun SimulatorsCard(
    simulators: List<SimulatorRow>,
    linkReason: String?,
) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.SIMULATORS), contentSpacing = OfSpacing.Md) {
        SectionTitle("SIMULATORS")
        when {
            simulators.isNotEmpty() -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                ) {
                    simulators.forEach { sim ->
                        OfPill(
                            label = "${sim.displayName} · ${sim.state}",
                            detail = sim.detail,
                            tone = sim.severity.tone(),
                            modifier = Modifier.testTag(SettingsTestTags.simulator(sim.target)),
                        )
                    }
                }
            }

            linkReason != null -> {
                OfDisabledReason(linkReason)
            }

            else -> {
                OfText(
                    text = "No simulator connectors configured on the Pi",
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                )
            }
        }
    }
}

private fun SimSeverity.tone(): StatusTone =
    when (this) {
        SimSeverity.OK -> StatusTone.Positive
        SimSeverity.WARN -> StatusTone.InProgress
        SimSeverity.ERROR -> StatusTone.Negative
        SimSeverity.OFF -> StatusTone.Neutral
    }

/** `DebugPanel.tsx`'s status and tuning tabs: trigger status, radar sliders and recent triggers. */
@Composable
internal fun RadarCard(
    radar: RadarPanel,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.RADAR), contentSpacing = OfSpacing.Md) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { SectionTitle("RADAR") }
            OfOutlinedButton(
                text = "Refresh",
                onClick = { onEvent(SettingsEvent.RefreshRadarConfig) },
                enabled = radar.refresh.isAvailable,
                modifier = Modifier.testTag(SettingsTestTags.RADAR_REFRESH),
            )
        }
        radar.refresh.disabledReason?.let { OfDisabledReason(it) }
        if (radar.config == null) {
            OfText(
                text = "No radar config from the Pi yet",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
            )
        }
        radar.tuningNotice?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.Warning) }
        radar.sliders.forEach { slider ->
            OfSlider(
                label = slider.label,
                value = slider.value,
                range = slider.min..slider.max,
                step = slider.step,
                unit = slider.unit,
                enabled = slider.availability.isAvailable,
                disabledReason = slider.availability.disabledReason,
                onValueCommitted = { onEvent(SettingsEvent.SetRadarValue(slider.field, it)) },
                modifier = Modifier.testTag(SettingsTestTags.slider(slider.field)),
            )
        }
        if (radar.sliders.isNotEmpty()) {
            OfText(text = radar.hint, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        }
        Diagnostics(radar.diagnostics)
    }
}

/** `DebugPanel.tsx`'s trigger history, newest first. */
@Composable
private fun Diagnostics(rows: List<TriggerDiagnosticRow>) {
    if (rows.isEmpty()) return
    OfDivider()
    OfText(text = "RECENT TRIGGERS", role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
    rows.forEach { row ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            OfText(
                text = if (row.accepted) "✓" else "✗",
                role = OfTextRole.TitleSmall,
                color = if (row.accepted) OfColorTokens.Success else OfColorTokens.Danger,
            )
            OfText(text = row.reasonText, role = OfTextRole.BodySmall, modifier = Modifier.weight(1f))
            row.timestamp?.let { clock(it) }?.let {
                OfText(text = it, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
            }
        }
    }
}

private fun clock(timestamp: String): String? =
    timestamp.substringAfter('T', missingDelimiterValue = "").take(CLOCK_LENGTH).ifEmpty { null }

/** `toggle_debug` and where the Pi writes its JSONL log while it's on. */
@Composable
internal fun DebugCard(
    debug: DebugSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("DEBUG")
        OfSwitchRow(
            label = "Debug logging",
            detail = "${debug.readingCount} readings · ${debug.shotLogCount} shot logs",
            checked = debug.enabled,
            onCheckedChange = { onEvent(SettingsEvent.ToggleDebug) },
            enabled = debug.toggle.isAvailable,
            disabledReason = debug.toggle.disabledReason,
            modifier = Modifier.testTag(SettingsTestTags.DEBUG_TOGGLE),
        )
        if (debug.enabled) {
            // A long path under its label, not beside it: a row would squeeze the label to a letter per line.
            debug.logPath?.let { path ->
                Column(modifier = Modifier.testTag(SettingsTestTags.DEBUG_LOG_PATH)) {
                    OfText(text = "Log file", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
                    OfText(text = path, role = OfTextRole.BodySmall)
                }
            }
        }
    }
}

/** `ShotList.tsx`'s "Upload Cloud" and its `cloud_upload_status`. */
@Composable
internal fun CloudCard(
    cloud: CloudSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        SectionTitle("FLIGHTWEB CLOUD")
        OfOutlinedButton(
            text = "Upload Session",
            onClick = { onEvent(SettingsEvent.UploadCloud) },
            enabled = cloud.upload.isAvailable,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CLOUD_UPLOAD),
        )
        cloud.upload.disabledReason?.let { OfDisabledReason(it) }
        if (cloud.state != CloudUploadState.IDLE || cloud.message != null) {
            OfPill(
                label = cloud.state.label(),
                detail = cloud.message,
                tone = cloud.state.tone(),
                modifier = Modifier.testTag(SettingsTestTags.CLOUD_STATUS),
            )
        }
    }
}

private fun CloudUploadState.label(): String =
    when (this) {
        CloudUploadState.IDLE -> "Idle"
        CloudUploadState.RUNNING -> "Uploading"
        CloudUploadState.COMPLETE -> "Uploaded"
        CloudUploadState.ERROR -> "Upload failed"
    }

private fun CloudUploadState.tone(): StatusTone =
    when (this) {
        CloudUploadState.IDLE -> StatusTone.Neutral
        CloudUploadState.RUNNING -> StatusTone.InProgress
        CloudUploadState.COMPLETE -> StatusTone.Positive
        CloudUploadState.ERROR -> StatusTone.Negative
    }
