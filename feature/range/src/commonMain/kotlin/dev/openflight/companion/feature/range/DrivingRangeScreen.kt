// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.model.ShotEvent

/**
 * The driving range (DrivingRangeView.swift): the 2.5D scene under a shading gradient, the metrics
 * overlay, the "Driving Range Ready" card before the first shot, and the exit / status / replay
 * controls. Stateless: everything comes from [uiState]; interactions go out through [onEvent] and
 * [onExit].
 */
@Composable
fun DrivingRangeScreen(
    uiState: DrivingRangeUiState,
    reduceMotion: Boolean,
    onEvent: (DrivingRangeEvent) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(OfColorTokens.BgDeep)) {
        val isLandscape = maxWidth > maxHeight
        RangeCanvas(
            flight = uiState.activeFlight,
            reduceMotion = reduceMotion,
            onFlightCompleted = { onEvent(DrivingRangeEvent.FlightCompleted) },
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Shade))
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            RangeMetricsOverlay(
                uiState = uiState,
                isLandscape = isLandscape,
                onSelectClub = { onEvent(DrivingRangeEvent.ClubSelected(it)) },
            )
            if (uiState is DrivingRangeUiState.Ready) {
                ReadyCard(modifier = Modifier.align(Alignment.Center))
            }
            Controls(
                uiState = uiState,
                onReplay = { onEvent(DrivingRangeEvent.Replay) },
                onExit = onExit,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Controls(
    uiState: DrivingRangeUiState,
    onReplay: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OfOutlinedButton(
            text = "Exit",
            onClick = onExit,
            modifier = Modifier.background(ControlBackground, PillShape).testTag(RangeTestTags.EXIT),
        )
        // The status takes the flexible space (and wraps if it must) so the buttons keep their width.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            OfStatusChip(
                label = uiState.phase.label,
                tone = uiState.phase.tone(),
                modifier =
                    Modifier
                        .background(ControlBackground, PillShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(RangeTestTags.STATUS),
            )
        }
        if (uiState.canReplay) {
            OfOutlinedButton(
                text = "Replay",
                onClick = onReplay,
                modifier = Modifier.background(ControlBackground, PillShape).testTag(RangeTestTags.REPLAY),
            )
        }
    }
}

@Composable
private fun ReadyCard(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .padding(24.dp)
                .width(320.dp)
                .background(Color.Black.copy(alpha = 0.62f), ReadyShape)
                .border(1.dp, Color.White.copy(alpha = 0.16f), ReadyShape)
                .padding(24.dp)
                .testTag(RangeTestTags.READY_CARD),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
    ) {
        OfText(text = "⛳", role = OfTextRole.Headline, color = OfColorTokens.Success)
        OfText(text = "Driving Range Ready", role = OfTextRole.Title, textAlign = TextAlign.Center)
        OfText(
            text = "Hit a shot and its flight will appear here.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            textAlign = TextAlign.Center,
        )
    }
}

/** DrivingRangeView.swift `statusSymbol`, as a tone. */
private fun RangePhase.tone(): StatusTone =
    when (this) {
        RangePhase.Waiting -> StatusTone.Neutral
        RangePhase.Preparing, RangePhase.Flying -> StatusTone.InProgress
        RangePhase.Landed -> StatusTone.Positive
        is RangePhase.Unavailable -> StatusTone.Negative
    }

private val Shade =
    Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.38f),
        0.5f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.60f),
    )
private val ControlBackground = Color.Black.copy(alpha = 0.6f)
private val PillShape = RoundedCornerShape(percent = 50)
private val ReadyShape = RoundedCornerShape(22.dp)

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
