// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus

/** A connected (or, with [link] ≠ Connected, a disconnected) settings state for previews. */
@Suppress("MagicNumber", "LongMethod") // Sample data.
internal fun previewSettingsState(
    link: PiLinkState = PiLinkState.Connected,
    transport: TransportType = TransportType.WIFI,
    confirmingShutdown: Boolean = false,
    debugEnabled: Boolean = true,
): SettingsUiState {
    val available = PiFeatureAvailability.of(link)
    val connected = available.isAvailable
    return SettingsUiState(
        transport = transport,
        host = "10.0.2.2:8098",
        connectionState = if (connected) ConnectionState.Connected else ConnectionState.Idle,
        linkState = link,
        linkDescription = link.description,
        units = UnitSystem.IMPERIAL,
        profile = ProfileSettings(activeName = if (connected) "Alex" else null, availability = available),
        simulators =
            if (connected) {
                listOf(SimulatorRow("gspro", "GSPro", "connected", SimSeverity.OK, "192.168.1.20:921"))
            } else {
                emptyList()
            },
        radar =
            RadarPanel(
                config =
                    if (connected) {
                        RadarConfig(
                            minSpeed = 10,
                            maxSpeed = 120,
                            minMagnitude = 400,
                            transmitPower = 0,
                        )
                    } else {
                        null
                    },
                triggerStatus =
                    if (connected) {
                        TriggerStatus(
                            mode = "rolling-buffer",
                            triggersTotal = 3,
                            triggersAccepted = 2,
                        )
                    } else {
                        null
                    },
                isSwingSpeedMode = false,
                sliders =
                    if (connected) {
                        listOf(
                            RadarSlider(RadarField.MIN_SPEED, "Min Speed", 10, 0, 50, 1, " mph", available),
                            RadarSlider(RadarField.MIN_MAGNITUDE, "Min Magnitude", 400, 0, 2000, 50, "", available),
                            RadarSlider(RadarField.TRANSMIT_POWER, "TX Power", 0, 0, 7, 1, "", available),
                        )
                    } else {
                        emptyList()
                    },
                tuningNotice = null,
                hint = "TX Power: 0 = max range, 7 = min range",
                diagnostics =
                    if (connected) {
                        listOf(
                            TriggerDiagnosticRow(
                                "2026-07-29T19:42:10",
                                true,
                                "Shot detected",
                                TriggerDiagnostic(
                                    timestamp = "2026-07-29T19:42:10",
                                    accepted = true,
                                    reason = "accepted",
                                ),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                refresh = available,
            ),
        debug = DebugSettings(debugEnabled && connected, "/tmp/openflight_debug.jsonl", 12, 1, available),
        cloud = CloudSettings(CloudUploadState.IDLE, null, available),
        shutdown = ShutdownSettings(confirmationRequired = confirmingShutdown && connected, shutdown = available),
        mockMode = connected,
    )
}

@Preview(heightDp = 1800)
@Composable
private fun SettingsConnectedPreview() {
    OfTheme { SettingsScreen(uiState = previewSettingsState(), onEvent = {}, onBack = {}) }
}

@Preview(heightDp = 1400)
@Composable
private fun SettingsBluetoothPreview() {
    OfTheme {
        SettingsScreen(
            uiState = previewSettingsState(link = PiLinkState.WifiOnly, transport = TransportType.BLUETOOTH),
            onEvent = {},
            onBack = {},
        )
    }
}
