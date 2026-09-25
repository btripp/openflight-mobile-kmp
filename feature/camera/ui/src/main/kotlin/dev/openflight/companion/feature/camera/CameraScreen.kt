// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfMessageHostState
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.model.ShotMetricFormatter

private const val FEED_ASPECT = 4f / 3f

/**
 * The camera screen (plan R8c): the polled preview of the Pi's high-speed camera ([frame], the
 * newest decoded still), the capture settings, and the shots whose captures can be replayed. A
 * prepared replay opens in the system's video player. Stateless: everything comes from
 * [uiState]/[frame] and goes out through [onEvent]/[onBack]. Polish is plan R8f.
 */
@Composable
fun CameraScreen(
    uiState: CameraUiState,
    frame: ImageBitmap?,
    onEvent: (CameraEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    messages: OfMessageHostState? = null,
) {
    OfScaffold(
        modifier = modifier,
        messages = messages,
        topBar = {
            OfTopBar(
                title = "Camera",
                eyebrow = "OPENFLIGHT",
                actions = {
                    OfOutlinedButton(
                        text = "Done",
                        onClick = onBack,
                        modifier = Modifier.padding(end = OfSpacing.Sm).testTag(CameraTestTags.DONE),
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
            Feed(uiState, frame)
            CaptureCard(uiState, onEvent)
            ReplaysCard(uiState, onEvent)
        }
    }
}

@Composable
private fun Feed(
    uiState: CameraUiState,
    frame: ImageBitmap?,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(FEED_ASPECT)
                .clip(RoundedCornerShape(20.dp))
                .background(OfColorTokens.BgElevated)
                .testTag(CameraTestTags.FEED),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uiState.phase == CameraPhase.LIVE && frame != null -> {
                Image(
                    bitmap = frame,
                    contentDescription = "Camera preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag(CameraTestTags.FRAME),
                )
            }

            uiState.phase == CameraPhase.LOADING || uiState.phase == CameraPhase.LIVE -> {
                OfSpinner(modifier = Modifier.size(32.dp))
            }

            else -> {
                PhaseMessage(uiState)
            }
        }
    }
}

@Composable
private fun PhaseMessage(uiState: CameraUiState) {
    val (title, body) = phaseCopy(uiState)
    Column(
        modifier = Modifier.padding(OfSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        OfText(
            text = title,
            role = OfTextRole.Title,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(CameraTestTags.PHASE_TITLE),
        )
        OfText(text = body, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim, textAlign = TextAlign.Center)
    }
}

internal fun phaseCopy(uiState: CameraUiState): Pair<String, String> =
    when (uiState.phase) {
        CameraPhase.OFFLINE -> {
            "Camera Offline" to
                "The camera needs the Pi's live Wi-Fi session (${uiState.availability.disabledReason.orEmpty()})."
        }

        CameraPhase.NOT_ENABLED -> {
            "Camera Capture Off" to "Start the Pi's server with high-speed camera capture to see its view here."
        }

        CameraPhase.NOT_RUNNING -> {
            "Camera Not Running" to "Capture is configured, but the camera isn't producing images."
        }

        CameraPhase.ERROR -> {
            "Preview Unavailable" to (uiState.previewError ?: CameraViewModel.PREVIEW_FAILED)
        }

        CameraPhase.LOADING, CameraPhase.LIVE -> {
            "" to ""
        }
    }

/** The Pi's `camera_capture_settings`: whether capture runs and is armed, and its format. */
@Composable
private fun CaptureCard(
    uiState: CameraUiState,
    onEvent: (CameraEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "CAPTURE", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        val settings = uiState.settings
        val status =
            when {
                settings == null -> "Not reported yet"
                !settings.available -> "Not enabled on the Pi"
                settings.armed == true -> "Armed"
                settings.running == true -> "Running"
                else -> "Stopped"
            }
        OfText(text = status, role = OfTextRole.TitleSmall, modifier = Modifier.testTag(CameraTestTags.CAPTURE_STATUS))
        uiState.captureSummary?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim) }
        settings?.error?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.Danger) }
        OfOutlinedButton(
            text = "Refresh",
            onClick = { onEvent(CameraEvent.RefreshSettings) },
            enabled = uiState.availability.isAvailable,
            modifier = Modifier.testTag(CameraTestTags.REFRESH),
        )
    }
}

/** Shots with a capture; a tap prepares the MP4 and opens it in the system player. */
@Composable
private fun ReplaysCard(
    uiState: CameraUiState,
    onEvent: (CameraEvent) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "REPLAYS", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        if (uiState.replays.isEmpty()) {
            OfText(
                text = "Shots with a high-speed capture appear here.",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
            )
        }
        for (row in uiState.replays) {
            ReplayRowItem(row, uiState.replay, uiState.availability.isAvailable, onEvent)
        }
        when (val replay = uiState.replay) {
            is CameraReplayState.Ready -> {
                OfButton(
                    text = "Open replay video",
                    onClick = { uriHandler.openUri(replay.videoUrl) },
                    modifier = Modifier.fillMaxWidth().testTag(CameraTestTags.OPEN_REPLAY),
                )
            }

            is CameraReplayState.Failed -> {
                OfText(
                    text = replay.message,
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.Danger,
                    modifier = Modifier.testTag(CameraTestTags.REPLAY_ERROR),
                )
            }

            else -> {
                Unit
            }
        }
    }
}

@Composable
private fun ReplayRowItem(
    row: ReplayRow,
    replay: CameraReplayState,
    enabled: Boolean,
    onEvent: (CameraEvent) -> Unit,
) {
    val preparing = replay is CameraReplayState.Preparing && replay.replayId == row.replayId
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
    ) {
        OfText(text = replayLabel(row), role = OfTextRole.BodySmall, modifier = Modifier.weight(1f))
        OfButton(
            text = "Play",
            onClick = { onEvent(CameraEvent.PlayReplay(row.replayId)) },
            enabled = enabled && replay !is CameraReplayState.Preparing,
            loading = preparing,
            modifier = Modifier.testTag(CameraTestTags.replay(row.replayId)),
        )
    }
}

private fun replayLabel(row: ReplayRow): String =
    listOfNotNull(
        row.shotNumber?.let { "#$it" },
        row.club,
        row.ballSpeedMph?.let { "${ShotMetricFormatter.number(it, 1)} mph" },
    ).joinToString(" · ")
