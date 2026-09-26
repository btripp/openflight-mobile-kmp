// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfIcon
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.core.model.ShotEvent

/**
 * The driving range (DrivingRangeView.swift): the 2.5D scene under a shading gradient, the metrics
 * overlay, the "Driving Range Ready" card before the first shot, and the exit / status / replay
 * controls. Stateless: everything comes from [uiState]; interactions go out through [onEvent] and
 * [onExit].
 *
 * @param windowClass injectable for tests and previews; defaults to [rememberOfWindowClass]. On an
 *   [OfWindowClass.EXPANDED] window in landscape the metrics move into a docked side panel
 *   ([MetricsDock]) instead of overlaying the scene (plan F1b, §4a A3).
 */
@Composable
fun DrivingRangeScreen(
    uiState: DrivingRangeUiState,
    reduceMotion: Boolean,
    onEvent: (DrivingRangeEvent) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    windowClass: OfWindowClass = rememberOfWindowClass(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(OfColorTokens.BgDeep)) {
        val isLandscape = maxWidth > maxHeight
        when (RangeOverlayLayout.of(windowClass, isLandscape)) {
            RangeOverlayLayout.OVERLAID -> {
                SceneLayer(
                    uiState = uiState,
                    reduceMotion = reduceMotion,
                    isLandscape = isLandscape,
                    onEvent = onEvent,
                    onExit = onExit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            RangeOverlayLayout.DOCKED -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    SceneLayer(
                        uiState = uiState,
                        reduceMotion = reduceMotion,
                        isLandscape = isLandscape,
                        onEvent = onEvent,
                        onExit = onExit,
                        showMetrics = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    MetricsDock(
                        uiState = uiState,
                        onEvent = onEvent,
                        modifier = Modifier.width(DockWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * The scene (the [RangeCanvas], its shading and, unless [showMetrics] is false, the metrics
 * overlaid on top), plus the exit/status/replay [Controls] and the ready card. Shared between the
 * full-bleed phone layout and the scene side of a docked tablet layout.
 */
@Composable
private fun SceneLayer(
    uiState: DrivingRangeUiState,
    reduceMotion: Boolean,
    isLandscape: Boolean,
    onEvent: (DrivingRangeEvent) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    showMetrics: Boolean = true,
) {
    Box(modifier = modifier) {
        RangeCanvas(
            flight = uiState.activeFlight,
            cameraMode = uiState.cameraMode,
            reduceMotion = reduceMotion,
            onFlightCompleted = { onEvent(DrivingRangeEvent.FlightCompleted) },
            modifier = Modifier.fillMaxSize(),
        )
        Box(modifier = Modifier.fillMaxSize().background(Shade))
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // Controls and the metrics overlay stack in a Column, not two independently
            // top-aligned Boxes: at a large system font scale, the status pill in Controls can
            // wrap onto 2-3 lines, and a fixed top padding on RangeMetricsOverlay (sized for a
            // single line) would then overlap it. Stacking lets the metrics row push down by
            // however tall Controls actually measures.
            Column(modifier = Modifier.fillMaxSize()) {
                Controls(
                    uiState = uiState,
                    onReplay = { onEvent(DrivingRangeEvent.Replay) },
                    onToggleCamera = { onEvent(DrivingRangeEvent.ToggleCameraMode) },
                    onExit = onExit,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                if (showMetrics) {
                    RangeMetricsOverlay(
                        uiState = uiState,
                        isLandscape = isLandscape,
                        onSelectClub = { onEvent(DrivingRangeEvent.ClubSelected(it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (uiState is DrivingRangeUiState.Ready) {
                ReadyCard(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * The metrics as a solid side panel next to the scene (plan F1b), instead of overlaid text: the
 * panel is narrow, so it always uses [RangeMetricsOverlay]'s portrait (stacked) metrics grid, not
 * its single landscape row. Scrollable and not [RangeMetricsOverlay.expandToFill]: a wide window
 * isn't necessarily a tall one (a phone rotated to landscape can be shorter than the metrics grid
 * needs), and the dock has no scene above or below to make room by shrinking.
 */
@Composable
private fun MetricsDock(
    uiState: DrivingRangeUiState,
    onEvent: (DrivingRangeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(DockBackground).testTag(RangeTestTags.METRICS_DOCK)) {
        RangeMetricsOverlay(
            uiState = uiState,
            isLandscape = false,
            onSelectClub = { onEvent(DrivingRangeEvent.ClubSelected(it)) },
            expandToFill = false,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun Controls(
    uiState: DrivingRangeUiState,
    onReplay: () -> Unit,
    onToggleCamera: () -> Unit,
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
        CameraModeToggle(
            mode = uiState.cameraMode,
            locked = uiState.cameraModeLocked,
            onToggle = onToggleCamera,
        )
        if (uiState.canReplay) {
            OfOutlinedButton(
                text = "Replay",
                onClick = onReplay,
                modifier = Modifier.background(ControlBackground, PillShape).testTag(RangeTestTags.REPLAY),
            )
        }
    }
}

/**
 * The camera-mode button (plan R7a): shows the camera in use, "Follow" or "Fixed", and switches to
 * the other one. Disabled (and fixed) while the system asks for reduced motion.
 */
@Composable
private fun CameraModeToggle(
    mode: RangeCameraMode,
    locked: Boolean,
    onToggle: () -> Unit,
) {
    val label =
        when (mode) {
            RangeCameraMode.FOLLOW -> "Follow"
            RangeCameraMode.FIXED -> "Fixed"
        }
    OfOutlinedButton(
        text = label,
        onClick = onToggle,
        enabled = !locked,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        leadingContent = {
            OfIcon(
                imageVector = OfIcons.Camera,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(end = 2.dp),
                tint = if (locked) OfColorTokens.CreamDim else OfColorTokens.Gold,
            )
        },
        modifier =
            Modifier
                .background(ControlBackground, PillShape)
                .semantics {
                    stateDescription = if (locked) "$label camera, fixed by reduced motion" else "$label camera"
                }.testTag(RangeTestTags.CAMERA_MODE),
    )
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

/** The docked metrics panel's fixed width (plan F1b): wide enough for the two-column metrics grid. */
private val DockWidth = 340.dp
private val DockBackground = OfColorTokens.BgCard

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
