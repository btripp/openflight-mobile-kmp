// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfDropdownMenu
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfMetricPrimary
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone

/**
 * Flip to `true` locally, rebuild and install, to view [DesignSystemGallery] in place
 * of [App] on-device. Must be `false` on every commit: the gallery is a debug-only
 * verification screen, never the app's real entry point.
 */
internal const val SHOW_DESIGN_SYSTEM_GALLERY = false

/** A debug-only screen that renders every `core:designsystem` component. */
@Composable
internal fun DesignSystemGallery() {
    OfTheme {
        OfScaffold(topBar = { OfTopBar(title = "Design System", eyebrow = "GALLERY") }) { padding ->
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(OfSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg),
            ) {
                item {
                    OfCard {
                        var transport by remember { mutableStateOf("Bluetooth") }
                        OfSegmentedPicker(
                            options = listOf("Bluetooth", "Wi-Fi"),
                            selected = transport,
                            onSelect = { transport = it },
                        )
                    }
                }
                item {
                    OfCard {
                        OfStatusChip(label = "OpenFlight Pi", tone = StatusTone.Positive, detail = "Connected")
                        OfStatusChip(label = "Bluetooth", tone = StatusTone.InProgress, detail = "Scanning…")
                        OfStatusChip(label = "Wi-Fi", tone = StatusTone.Negative, detail = "Too many devices")
                        OfStatusChip(label = "Idle", tone = StatusTone.Neutral, detail = "Not connected")
                    }
                }
                item {
                    OfCard {
                        var host by remember { mutableStateOf("raspberrypi.local:8080") }
                        OfTextField(value = host, onValueChange = { host = it }, monospace = true)
                    }
                }
                item {
                    OfCard {
                        var club by remember { mutableStateOf("7-Iron") }
                        OfDropdownMenu(
                            label = "NEXT CLUB",
                            selected = club,
                            options = listOf("Driver", "3-Wood", "7-Iron", "Pitching Wedge"),
                            onSelect = { club = it },
                        )
                    }
                }
                item {
                    OfCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                            OfMetricPrimary(title = "BALL SPEED", value = "142.3", unit = "MPH")
                            OfMetricPrimary(title = "CARRY", value = "231", unit = "YDS")
                        }
                    }
                }
                item {
                    OfCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Xxl)) {
                            OfMetricDetail(title = "Club speed", value = "104.1", unit = "mph")
                            OfMetricDetail(title = "Smash", value = "—")
                        }
                    }
                }
                item {
                    OfCard {
                        OfButton(text = "Retry", onClick = {})
                        OfButton(text = "Applying", onClick = {}, loading = true)
                        OfOutlinedButton(text = "Calibrate TI Radar", onClick = {})
                    }
                }
            }
        }
    }
}
