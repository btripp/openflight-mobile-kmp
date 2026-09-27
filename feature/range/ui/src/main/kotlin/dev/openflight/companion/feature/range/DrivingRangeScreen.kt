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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfIcon
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfListDetailPane
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfStatusChip
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass

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
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val browse = uiState.browse
    if (windowClass == OfWindowClass.EXPANDED && !browse.isLive) {
        // Plan F8a1: on a tablet, the replay/overlay shots sit in a side pane; a tap selects one.
        OfListDetailPane(
            hasSelection = true,
            windowClass = windowClass,
            listFraction = SHOT_LIST_FRACTION,
            modifier = modifier,
            list = { RangeShotList(browse, onSelect = { onEvent(DrivingRangeEvent.SelectShot(it)) }) },
            detail = {
                RangeStage(
                    uiState,
                    reduceMotion,
                    onEvent,
                    onExit,
                    windowClass,
                    onOpenSessions = { showSessions = true },
                )
            },
        )
    } else {
        RangeStage(
            uiState,
            reduceMotion,
            onEvent,
            onExit,
            windowClass,
            onOpenSessions = { showSessions = true },
            modifier,
        )
    }
    if (showSessions) {
        RangeSessionSheet(sessions = browse.sessions, onEvent = onEvent, onDismiss = { showSessions = false })
    }
}

/** The scene with its overlays: everything but the tablet side pane. */
@Composable
private fun RangeStage(
    uiState: DrivingRangeUiState,
    reduceMotion: Boolean,
    onEvent: (DrivingRangeEvent) -> Unit,
    onExit: () -> Unit,
    windowClass: OfWindowClass,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
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
                    onOpenSessions = onOpenSessions,
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
                        onOpenSessions = onOpenSessions,
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
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    showMetrics: Boolean = true,
) {
    val browse = uiState.browse
    Box(modifier = modifier) {
        RangeCanvas(
            flight = uiState.activeFlight,
            cameraMode = uiState.cameraMode,
            reduceMotion = reduceMotion,
            onFlightCompleted = { onEvent(DrivingRangeEvent.FlightCompleted) },
            modifier = Modifier.fillMaxSize(),
            view = browse.view,
            rollOut = uiState.rollOut,
            overlay = browse.overlayFlights,
            overlayMode = browse.mode is RangeMode.Overlay,
            selectedOverlayId = if (browse.mode is RangeMode.Overlay) browse.selectedShotId else null,
            onViewChanged = { onEvent(DrivingRangeEvent.ViewChanged(it)) },
            onResetView = { onEvent(DrivingRangeEvent.ResetView) },
            onSelectLanding = { onEvent(DrivingRangeEvent.SelectShot(it)) },
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
                    onOpenSessions = onOpenSessions,
                    onExit = onExit,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                BrowseChips(uiState, onEvent, Modifier.padding(horizontal = 14.dp))
                if (showMetrics) {
                    RangeMetricsOverlay(
                        uiState = uiState,
                        isLandscape = isLandscape,
                        onSelectClub = { onEvent(DrivingRangeEvent.ClubSelected(it)) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Plan F1b: the metrics are docked beside the scene; keep the replay bar at the bottom.
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (!browse.isLive) {
                    RangeBrowseBar(
                        browse = browse,
                        onEvent = onEvent,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }
            if (uiState is DrivingRangeUiState.Ready && browse.isLive) {
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
    onOpenSessions: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plan F8d-B: the status pill sits between Exit and the buttons only when its whole label fits
    // there on one line; otherwise it drops to its own line under them ([ControlsLayout]) instead
    // of being squeezed into a column of letters (large text, or Follow + History + Replay).
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            OfOutlinedButton(
                text = "Exit",
                onClick = onExit,
                modifier = Modifier.background(ControlBackground, PillShape).testTag(RangeTestTags.EXIT),
            )
            OfStatusChip(
                label = uiState.phase.label,
                tone = uiState.phase.tone(),
                modifier =
                    Modifier
                        .background(ControlBackground, PillShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(RangeTestTags.STATUS),
            )
            ControlButtons(uiState, onReplay, onToggleCamera, onOpenSessions)
        },
        measurePolicy = ControlsLayout(gap = 10.dp),
    )
}

/**
 * Lays out Exit, the status pill and the trailing buttons: one row when the pill's natural,
 * one-line width fits between Exit and the buttons; else Exit and the buttons on the first row and
 * the pill end-aligned on a second row, with the full width to wrap in if it still must.
 */
private class ControlsLayout(
    private val gap: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val (exitMeasurable, statusMeasurable, buttonsMeasurable) = measurables
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val exit = exitMeasurable.measure(loose)
        val buttons =
            buttonsMeasurable.measure(
                loose.copy(maxWidth = (constraints.maxWidth - exit.width - gapPx).coerceAtLeast(0)),
            )
        val between = constraints.maxWidth - exit.width - buttons.width - 2 * gapPx
        val oneRow = statusMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= between
        val status = statusMeasurable.measure(loose.copy(maxWidth = if (oneRow) between else constraints.maxWidth))
        val rowHeight = maxOf(exit.height, buttons.height, if (oneRow) status.height else 0)
        val height = if (oneRow) rowHeight else rowHeight + gapPx + status.height
        val width = constraints.maxWidth
        return layout(width, height) {
            exit.place(0, (rowHeight - exit.height) / 2)
            buttons.place(width - buttons.width, (rowHeight - buttons.height) / 2)
            if (oneRow) {
                status.place(width - buttons.width - gapPx - status.width, (rowHeight - status.height) / 2)
            } else {
                status.place(width - status.width, rowHeight + gapPx)
            }
        }
    }
}

/** Follow/Fixed, History and (when there's a shot to fly again) Replay. */
@Composable
private fun ControlButtons(
    uiState: DrivingRangeUiState,
    onReplay: () -> Unit,
    onToggleCamera: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CameraModeToggle(
            mode = uiState.cameraMode,
            locked = uiState.cameraModeLocked,
            onToggle = onToggleCamera,
        )
        OfOutlinedButton(
            text = "History",
            onClick = onOpenSessions,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.background(ControlBackground, PillShape).testTag(RangeTestTags.HISTORY),
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

/** The scrim over the scene that keeps the controls readable (plan F8c1: shared with iOS). */
private val Shade =
    RangeTheme.DAY.style.shade
        .toVerticalBrush()
private val ControlBackground = Color.Black.copy(alpha = 0.6f)
private val PillShape = RoundedCornerShape(percent = 50)
private val ReadyShape = RoundedCornerShape(22.dp)
private const val SHOT_LIST_FRACTION = 0.3f

/** The docked metrics panel's fixed width (plan F1b): wide enough for the two-column metrics grid. */
private val DockWidth = 340.dp
private val DockBackground = OfColorTokens.BgCard
