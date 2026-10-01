// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub

/** Plan F8a1: the replay transport or the overlay's bar, at the bottom of the scene. */
@Composable
internal fun RangeBrowseBar(
    browse: RangeBrowseState,
    onEvent: (DrivingRangeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(BarBackground, BarShape)
                .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Sm)
                .testTag(RangeTestTags.TRANSPORT),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
        ) {
            SmallButton("Live", RangeTestTags.LIVE) { onEvent(DrivingRangeEvent.ReturnToLive) }
            when (val mode = browse.mode) {
                is RangeMode.Replay -> ReplayTransport(browse, mode, onEvent)
                is RangeMode.Overlay -> OverlayControls(browse, mode, onEvent)
                RangeMode.Live -> Unit
            }
        }
        if (browse.mode is RangeMode.Overlay && browse.overlayTruncated) {
            OfText(
                text = "Showing the newest ${RangeBrowseState.OVERLAY_CAP} shots",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.testTag(RangeTestTags.OVERLAY_TRUNCATED),
            )
        }
    }
}

@Composable
private fun RowScope.ReplayTransport(
    browse: RangeBrowseState,
    mode: RangeMode.Replay,
    onEvent: (DrivingRangeEvent) -> Unit,
) {
    val count = browse.shots.size
    SmallButton("Prev", RangeTestTags.PREVIOUS, enabled = mode.index > 0) { onEvent(DrivingRangeEvent.PreviousShot) }
    SmallButton(if (browse.playing) "Pause" else "Play", RangeTestTags.PLAY_PAUSE, enabled = count > 0) {
        onEvent(DrivingRangeEvent.PlayPause)
    }
    SmallButton("Next", RangeTestTags.NEXT, enabled = mode.index + 1 < count) { onEvent(DrivingRangeEvent.NextShot) }
    OfText(
        text =
            if (browse.loading) {
                "Loading…"
            } else if (count == 0) {
                "No shots"
            } else {
                "${mode.index + 1} / $count"
            },
        role = OfTextRole.BodySmall,
        color = OfColorTokens.Cream,
        modifier = Modifier.testTag(RangeTestTags.POSITION),
    )
    for (speed in ReplaySpeed.entries) {
        OfChip(
            label = speed.label,
            selected = browse.speed == speed,
            minTouchTarget = true,
            onClick = { onEvent(DrivingRangeEvent.SetSpeed(speed)) },
            modifier = Modifier.testTag(RangeTestTags.speed(speed)),
        )
    }
}

@Composable
private fun RowScope.OverlayControls(
    browse: RangeBrowseState,
    mode: RangeMode.Overlay,
    onEvent: (DrivingRangeEvent) -> Unit,
) {
    mode.sessionId?.let { sessionId ->
        SmallButton("Replay session", RangeTestTags.REPLAY_SESSION) {
            onEvent(DrivingRangeEvent.StartReplay(sessionId))
        }
    }
    OfChip(
        label = "All clubs",
        count = if (mode.club == null && !browse.loading) browse.overlayFlights.size else null,
        selected = mode.club == null,
        minTouchTarget = true,
        onClick = { onEvent(DrivingRangeEvent.SetOverlayClub(null)) },
        modifier = Modifier.testTag(RangeTestTags.overlayClub(null)),
    )
    for (club in browse.overlayClubs) {
        OfChip(
            label = GolfClub.displayNameFor(club),
            selected = mode.club == club,
            minTouchTarget = true,
            onClick = { onEvent(DrivingRangeEvent.SetOverlayClub(club)) },
            modifier = Modifier.testTag(RangeTestTags.overlayClub(club)),
        )
    }
}

@Composable
private fun SmallButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OfOutlinedButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.testTag(tag),
    )
}

/**
 * The session picker (plan F8a1), from `ShotHistoryRepository.sessions()`: replay or overlay one
 * session, or overlay every session.
 */
@Composable
internal fun RangeSessionSheet(
    sessions: List<RangeSessionOption>,
    onEvent: (DrivingRangeEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    OfBottomSheet(onDismiss = onDismiss, modifier = Modifier.testTag(RangeTestTags.SESSION_SHEET)) {
        OfText(text = "Replay a session", role = OfTextRole.Title)
        if (sessions.isEmpty()) {
            OfText(
                text = "No stored sessions yet. Hit some shots and they'll be here.",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.testTag(RangeTestTags.SESSIONS_EMPTY),
            )
            return@OfBottomSheet
        }
        OfOutlinedButton(
            text = "Overlay all sessions",
            onClick = {
                onEvent(DrivingRangeEvent.StartOverlay(sessionId = null))
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().testTag(RangeTestTags.OVERLAY_ALL),
        )
        for (session in sessions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OfText(text = session.title, role = OfTextRole.Body)
                    OfText(
                        text = "${session.shotCount} shots",
                        role = OfTextRole.BodySmall,
                        color = OfColorTokens.CreamDim,
                    )
                }
                SmallButton("Replay", RangeTestTags.replaySession(session.id)) {
                    onEvent(DrivingRangeEvent.StartReplay(session.id))
                    onDismiss()
                }
                SmallButton("Overlay", RangeTestTags.overlaySession(session.id)) {
                    onEvent(DrivingRangeEvent.StartOverlay(session.id))
                    onDismiss()
                }
            }
        }
    }
}

/** The side pane on expanded windows: the replay or overlay shots; tapping one selects it. */
@Composable
internal fun RangeShotList(
    browse: RangeBrowseState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    numbers: RangeNumbers = RangeNumbers(),
) {
    val distanceUnit = if (numbers.units == UnitSystem.METRIC) "m" else "yd"
    LazyColumn(
        modifier = modifier.fillMaxSize().background(OfColorTokens.BgDeep).testTag(RangeTestTags.SHOT_LIST),
        contentPadding = PaddingValues(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        item(key = "header") {
            OfText(
                text = if (browse.mode is RangeMode.Overlay) "OVERLAY · newest first" else "REPLAY · in order",
                role = OfTextRole.Eyebrow,
                color = OfColorTokens.Gold,
            )
        }
        items(browse.shots, key = { it.id }) { shot ->
            val selected = shot.id == browse.selectedShotId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) OfColorTokens.BgHover else Color.Transparent, RowShape)
                        .clickable(role = Role.Button) { onSelect(shot.id) }
                        .semantics { this.selected = selected }
                        .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Sm)
                        .testTag(RangeTestTags.shot(shot.id)),
                horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OfText(text = "#${shot.number}", role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
                OfText(text = shot.clubLabel, role = OfTextRole.Body, modifier = Modifier.weight(1f))
                OfText(
                    text = "${numbers.distance(shot.carryYards)} $distanceUnit",
                    role = OfTextRole.Body,
                    color = if (selected) OfColorTokens.Gold else OfColorTokens.Cream,
                )
            }
        }
    }
}

/**
 * Plan F8a1 chips under the controls: "New shot · Return to live", "Reset view" while the user has
 * moved the camera, and the estimated total ("est.", plan F2) once the ball is down.
 */
@Composable
internal fun BrowseChips(
    uiState: DrivingRangeUiState,
    onEvent: (DrivingRangeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val browse = uiState.browse
    val rollOut = uiState.rollOut?.takeIf { uiState.phase == RangePhase.Landed || uiState.phase == RangePhase.Waiting }
    val simulateError = uiState.simulateError
    val hasChip = browse.newLiveShot || browse.userTransformed || rollOut != null
    val hasSimulate = uiState.canSimulate || simulateError != null
    if (!hasChip && !hasSimulate) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        simulateError?.let { error ->
            OfText(
                text = "⚠ $error",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Danger,
                modifier = Modifier.align(Alignment.CenterVertically).testTag(RangeTestTags.SIMULATE_ERROR),
            )
        }
        if (browse.newLiveShot) {
            OfChip(
                label = "New shot · Return to live",
                selected = true,
                minTouchTarget = true,
                onClick = { onEvent(DrivingRangeEvent.ReturnToLive) },
                modifier = Modifier.testTag(RangeTestTags.NEW_LIVE_SHOT),
            )
        }
        if (browse.userTransformed) {
            OfChip(
                label = "Reset view",
                minTouchTarget = true,
                onClick = { onEvent(DrivingRangeEvent.ResetView) },
                modifier = Modifier.testTag(RangeTestTags.RESET_VIEW),
            )
        }
        if (rollOut != null) {
            OfChip(
                label = uiState.camera.numbers.rollOutSummary(rollOut),
                modifier = Modifier.testTag(RangeTestTags.ROLL_OUT),
            )
        }
        // Plan F8d: a `--mock` Pi over Wi-Fi flies a new simulated shot through the live path. Last,
        // so it stays at the row's end while the other chips come and go.
        if (uiState.canSimulate) {
            OfOutlinedButton(
                text = "Simulate shot",
                onClick = { onEvent(DrivingRangeEvent.SimulateShot) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.background(ChipBackground, PillShape).testTag(RangeTestTags.SIMULATE),
            )
        }
    }
}

private val BarBackground = Color.Black.copy(alpha = 0.62f)
private val BarShape = RoundedCornerShape(18.dp)
private val RowShape = RoundedCornerShape(10.dp)
private val ChipBackground = Color.Black.copy(alpha = 0.6f)
private val PillShape = RoundedCornerShape(percent = 50)
