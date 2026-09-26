// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.ShotEvent

@Preview
@Composable
private fun DrivingRangeReadyPreview() {
    OfTheme {
        DrivingRangeScreen(uiState = DrivingRangeUiState.Ready(), reduceMotion = false, onEvent = {}, onExit = {})
    }
}

@Preview
@Composable
private fun DrivingRangeShotPreview() {
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
        DrivingRangeScreen(
            uiState = DrivingRangeUiState.Showing(shot, RangePhase.Landed, activeFlight = null),
            reduceMotion = false,
            onEvent = {},
            onExit = {},
        )
    }
}

private val previewReplay =
    RangeBrowseState(
        mode = RangeMode.Replay(sessionId = "s1", index = 1),
        shots =
            listOf(
                RangeShotItem("1", 1, "driver", "Driver", 262.0, 150.1, "2026-09-25T10:03:00", flyable = true),
                RangeShotItem("2", 2, "7-iron", "7-Iron", 171.0, 118.4, "2026-09-25T10:04:00", flyable = true),
                RangeShotItem("3", 3, "pw", "Pitching Wedge", 128.0, 96.0, "2026-09-25T10:05:00", flyable = true),
            ),
        selectedShotId = "2",
        playing = true,
        newLiveShot = true,
    )

@Preview
@Composable
private fun DrivingRangeReplayPreview() {
    OfTheme {
        DrivingRangeScreen(
            uiState = DrivingRangeUiState.Ready(browse = previewReplay),
            reduceMotion = false,
            onEvent = {},
            onExit = {},
            windowClass = OfWindowClass.COMPACT,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun DrivingRangeReplayTabletPreview() {
    OfTheme {
        DrivingRangeScreen(
            uiState = DrivingRangeUiState.Ready(browse = previewReplay),
            reduceMotion = false,
            onEvent = {},
            onExit = {},
            windowClass = OfWindowClass.EXPANDED,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun DrivingRangeDockedPreview() {
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
        DrivingRangeScreen(
            uiState = DrivingRangeUiState.Showing(shot, RangePhase.Landed, activeFlight = null),
            reduceMotion = false,
            onEvent = {},
            onExit = {},
            windowClass = OfWindowClass.EXPANDED,
        )
    }
}
