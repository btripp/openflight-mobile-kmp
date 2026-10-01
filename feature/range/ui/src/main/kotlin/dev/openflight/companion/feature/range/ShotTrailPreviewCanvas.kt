// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.rememberOfReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plan F8a2t: the Settings › Practice "Shot trail" preview. The range's own [RangeCanvas] and shared
 * [ShotTrail] fly [ShotTrailPreview]'s 7-iron on a loop in [style] over [theme], with its earlier
 * shots when [keepLast] asks for them and the [landingEffect] at each landing. Under reduced motion
 * it shows the landed shot, still. It takes no gestures, so the settings list scrolls over it.
 *
 * `androidApp` hands it to the settings screen (a feature never depends on another feature).
 */
@Composable
fun ShotTrailPreviewCanvas(
    style: ShotTrailStyle,
    keepLast: Int,
    landingEffect: LandingEffect,
    theme: RangeThemeSetting,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberOfReduceMotion(),
) {
    // The four preview flights are simulated once, off the main thread.
    val ready by produceState(initialValue = false) {
        withContext(Dispatchers.Default) { ShotTrailPreview.flight(0) }
        value = true
    }
    var playbackId by remember { mutableLongStateOf(1L) }
    var landed by remember { mutableStateOf(false) }
    val flight = if (ready) remember(playbackId) { ShotTrailPreview.flight(playbackId) } else null
    val trail =
        remember(style, keepLast, landingEffect, ready) {
            if (ready) {
                ShotTrailPreview.trail(style, keepLast, landingEffect)
            } else {
                RangeTrailState(style = style, keepLast = keepLast, landingEffect = landingEffect)
            }
        }
    LaunchedEffect(landed) {
        if (!landed) return@LaunchedEffect
        delay(ShotTrailPreview.LOOP_PAUSE_MILLIS)
        playbackId++
        landed = false
    }
    RangeCanvas(
        flight = flight,
        cameraMode = RangeCameraMode.FIXED,
        reduceMotion = reduceMotion,
        onFlightComplete = { if (!reduceMotion) landed = true },
        modifier =
            modifier
                .clip(RoundedCornerShape(PREVIEW_CORNER_DP.dp))
                .semantics { contentDescription = "Shot trail preview" },
        view = ShotTrailPreview.VIEW,
        theme = RangeTheme.of(theme),
        freezeProgress = if (reduceMotion) 1f else null,
        trail = trail,
        interactive = false,
        sceneTag = RangeTestTags.TRAIL_PREVIEW,
    )
}

private const val PREVIEW_CORNER_DP = 12

@Preview
@Composable
private fun ShotTrailPreviewCanvasPreview() {
    OfTheme {
        ShotTrailPreviewCanvas(
            style = ShotTrailStyle.COMET,
            keepLast = 3,
            landingEffect = LandingEffect.RING,
            theme = RangeThemeSetting.DAY,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            reduceMotion = true,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ShotTrailPreviewCanvasTabletPreview() {
    OfTheme {
        ShotTrailPreviewCanvas(
            style = ShotTrailStyle.SPIN_RIBBON,
            keepLast = 0,
            landingEffect = LandingEffect.BURST,
            theme = RangeThemeSetting.NIGHT,
            modifier = Modifier.fillMaxWidth().height(240.dp),
            reduceMotion = true,
        )
    }
}
