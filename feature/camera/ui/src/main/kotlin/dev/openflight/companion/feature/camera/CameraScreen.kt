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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfLinearProgress
import dev.openflight.companion.core.designsystem.OfMessageHostState
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTopBar

private const val FEED_ASPECT = 4f / 3f
private const val PERCENT = 100f

/**
 * The camera screen (plan R6c), ported from the web UI's `CameraFeed.tsx` and
 * `BallDetectionIndicator.tsx`: the live MJPEG feed ([frame], the newest decoded JPEG), the ball
 * detection status with its confidence, and the camera/stream toggles, each disabled with the VM's
 * reason. Stateless: everything comes from [uiState]/[frame] and goes out through [onEvent]/[onBack].
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
            Feed(uiState, frame, onRetry = { onEvent(CameraEvent.RetryStream) })
            BallDetectionCard(uiState)
            TogglesCard(uiState, onEvent)
        }
    }
}

@Composable
private fun Feed(
    uiState: CameraUiState,
    frame: ImageBitmap?,
    onRetry: () -> Unit,
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
        if (uiState.phase == CameraPhase.STREAMING) {
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Live camera feed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag(CameraTestTags.FRAME),
                )
            } else {
                OfSpinner(modifier = Modifier.size(32.dp))
            }
        } else {
            PhaseMessage(uiState, onRetry)
        }
    }
}

/** `CameraFeed.tsx`'s placeholder for every phase but streaming. */
@Composable
private fun PhaseMessage(
    uiState: CameraUiState,
    onRetry: () -> Unit,
) {
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
        uiState.cameraError?.let {
            OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.Danger, textAlign = TextAlign.Center)
        }
        if (uiState.phase == CameraPhase.STREAM_ERROR) {
            OfOutlinedButton(text = "Retry", onClick = onRetry, modifier = Modifier.testTag(CameraTestTags.RETRY))
        }
    }
}

private fun phaseCopy(uiState: CameraUiState): Pair<String, String> =
    when (uiState.phase) {
        CameraPhase.OFFLINE -> {
            "Camera Offline" to
                "The camera needs the Pi's live Wi-Fi session (${uiState.availability.disabledReason.orEmpty()})."
        }

        CameraPhase.UNAVAILABLE -> {
            "Camera Not Available" to "Start the Pi's server with the --camera flag to enable camera support."
        }

        CameraPhase.DISABLED -> {
            "Camera Disabled" to "Turn on ball detection to start the camera."
        }

        CameraPhase.PAUSED -> {
            "Stream Paused" to "Ball detection is active. Turn on the live stream to watch the feed."
        }

        CameraPhase.STREAM_ERROR -> {
            "Stream Error" to (uiState.streamError ?: CameraViewModel.STREAM_FAILED)
        }

        CameraPhase.STREAMING -> {
            "" to ""
        }
    }

/** `BallDetectionIndicator.tsx`: a status dot, "Ball 87%" / "No Ball" / "Camera Off", and the confidence. */
@Composable
private fun BallDetectionCard(uiState: CameraUiState) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "BALL DETECTION", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
            modifier =
                Modifier
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .testTag(CameraTestTags.BALL_STATUS),
        ) {
            val dot =
                when {
                    !uiState.enabled -> OfColorTokens.Neutral
                    uiState.ballDetected -> OfColorTokens.Success
                    else -> OfColorTokens.Warning
                }
            Box(modifier = Modifier.size(12.dp).background(dot, CircleShape))
            OfText(text = uiState.statusText, role = OfTextRole.TitleSmall)
        }
        if (uiState.enabled) {
            OfLinearProgress(
                progress = uiState.ballConfidencePercent / PERCENT,
                modifier = Modifier.fillMaxWidth(),
            )
            OfText(
                text = "Confidence ${uiState.ballConfidencePercent}%",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
            )
        }
    }
}

@Composable
private fun TogglesCard(
    uiState: CameraUiState,
    onEvent: (CameraEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth(), contentSpacing = OfSpacing.Md) {
        OfText(text = "CONTROLS", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        OfSwitchRow(
            label = "Ball detection",
            detail = "Runs the camera and looks for the ball",
            checked = uiState.enabled,
            onCheckedChange = { onEvent(CameraEvent.ToggleCamera) },
            enabled = uiState.toggleCamera.isAvailable,
            disabledReason = uiState.toggleCamera.disabledReason,
            modifier = Modifier.testTag(CameraTestTags.TOGGLE_CAMERA),
        )
        OfSwitchRow(
            label = "Live stream",
            detail = "Shows the camera's view above",
            checked = uiState.streaming,
            onCheckedChange = { onEvent(CameraEvent.ToggleStream) },
            enabled = uiState.toggleStream.isAvailable,
            disabledReason = uiState.toggleStream.disabledReason,
            modifier = Modifier.testTag(CameraTestTags.TOGGLE_STREAM),
        )
    }
}
