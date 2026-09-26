// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.SpinSource
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent

private fun previewLiveUiState(): DashboardUiState.Live {
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
    return DashboardUiState.Live(
        connection = ConnectionPanelState(transport = TransportType.WIFI, state = ConnectionState.Connected),
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
    )
}

@Preview
@Composable
private fun DashboardLivePreview() {
    OfTheme {
        DashboardScreen(
            uiState = previewLiveUiState(),
            onEvent = {},
            onOpenCalibration = {},
            onOpenRange = {},
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun DashboardExpandedPreview() {
    OfTheme {
        DashboardScreen(
            uiState = previewLiveUiState(),
            onEvent = {},
            onOpenCalibration = {},
            onOpenRange = {},
            windowClass = OfWindowClass.EXPANDED,
        )
    }
}
