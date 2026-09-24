// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.flight.RangeQualityProfile
import dev.openflight.companion.core.flight.RangeSceneDescription

/**
 * The 2.5D range: the scene, the one tracer, the ball, its shadow and the landing marker, all
 * drawn on a `Canvas` through [RangeProjection] (the RealityKit scene in the reference).
 *
 * A new [ActiveFlight.playbackId] starts a playback; it runs on the frame clock for
 * [playbackSeconds] and then calls [onFlightCompleted]. After that the frame clock keeps running
 * for [RangeCameraRig.settleSeconds] so the follow camera can settle over the landing spot. A
 * `null` [flight] (landed and dwelt, or suspended) freezes the last flight where it is, like the
 * reference's `suspend()`, until the next playback replaces it: only one tracer is ever drawn.
 *
 * Every frame asks [RangeCameraRig] for the pose of [cameraMode] (plan R7a). The scene and the
 * flight are kept in world space and re-projected only when the pose or the canvas changes: every
 * frame while the follow camera moves, once per size for the fixed camera. Re-projecting rewrites
 * the same `Path`s and float arrays, so a frame allocates nothing but the planner's small pose.
 */
@Composable
fun RangeCanvas(
    flight: ActiveFlight?,
    cameraMode: RangeCameraMode,
    reduceMotion: Boolean,
    onFlightCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<ActiveFlight?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }
    val landedSeconds = remember { mutableFloatStateOf(0f) }
    val mode by rememberUpdatedState(cameraMode)
    val completed by rememberUpdatedState(onFlightCompleted)
    val quality = RangeQualityProfile.BALANCED
    val rig = remember { RangeCameraRig() }
    val renderer = remember { RangeRenderer(RangeScene(RangeSceneDescription.standard(quality.treeCount))) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(flight?.playbackId) {
        val playing = flight
        if (playing != null) {
            shown = playing
            progress.floatValue = 0f
            landedSeconds.floatValue = 0f
            val durationNanos = playbackSeconds(playing.trajectory, reduceMotion) * NANOS_PER_SECOND
            val start = withFrameNanos { it }
            while (progress.floatValue < 1f) {
                val now = withFrameNanos { it }
                progress.floatValue = ((now - start) / durationNanos).toFloat().coerceIn(0f, 1f)
            }
            completed()
        }
        // Landed (this flight, or the one whose dwell just ended): let the camera finish settling.
        // A flight suspended mid-air stays frozen instead.
        if (shown == null || progress.floatValue < 1f) return@LaunchedEffect
        // Compared as Float on both sides: a Float that has been clamped to the settle time can
        // still be below the same value as a Double, which would never end the loop.
        val settleSeconds = rig.settleSeconds.toFloat()
        val landedAt = withFrameNanos { it } - (landedSeconds.floatValue * NANOS_PER_SECOND).toLong()
        while (landedSeconds.floatValue < settleSeconds) {
            val now = withFrameNanos { it }
            landedSeconds.floatValue = ((now - landedAt) / NANOS_PER_SECOND).toFloat().coerceAtMost(settleSeconds)
        }
    }

    Spacer(
        modifier =
            modifier.testTag(RangeTestTags.SCENE).drawWithCache {
                renderer.resize(size.width, size.height, rig.fixedPose)
                renderer.setFlight(shown, quality.tracerPointCount)
                renderer.labelLayouts =
                    renderer.scene.labels.map { label ->
                        textMeasurer.measure(label.text, labelStyle.copy(fontSize = MAX_LABEL_SP.sp))
                    }
                renderer.labelFontPixels = MAX_LABEL_SP.sp.toPx()
                onDrawBehind {
                    val current = shown
                    val pose =
                        rig.pose(
                            mode,
                            current?.trajectory,
                            progress.floatValue.toDouble(),
                            landedSeconds.floatValue.toDouble(),
                        )
                    renderer.draw(this, pose, progress.floatValue, LABEL_HEIGHT_METERS, MIN_LABEL_SP.sp.toPx())
                }
            },
    )
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val LABEL_HEIGHT_METERS = 2.2f
private const val MIN_LABEL_SP = 9f
private const val MAX_LABEL_SP = 16f
private val labelStyle = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
