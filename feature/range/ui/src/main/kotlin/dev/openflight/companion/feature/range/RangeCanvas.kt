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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeQualityProfile
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.RangeTracerStyle

/**
 * The 2.5D range: the static scene, the one tracer, the ball, its shadow and the landing marker,
 * all drawn on a `Canvas` through [RangeProjection] (the RealityKit scene in the reference).
 *
 * A new [ActiveFlight.playbackId] starts a playback; it runs on the frame clock for
 * [playbackSeconds] and then calls [onFlightCompleted]. A `null` [flight] (landed and dwelt, or
 * suspended) freezes the last flight where it is, like the reference's `suspend()`, until the next
 * playback replaces it: only one tracer is ever drawn.
 *
 * The scene and the flight's projected samples are built once per canvas size (and per flight);
 * a frame only reads [progress] and draws.
 */
@Composable
fun RangeCanvas(
    flight: ActiveFlight?,
    reduceMotion: Boolean,
    onFlightCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf<ActiveFlight?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }
    val completed by rememberUpdatedState(onFlightCompleted)
    val quality = RangeQualityProfile.BALANCED
    val description = remember { RangeSceneDescription.standard(quality.treeCount) }
    val pose = remember { RangeCameraPlanner().pose }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(flight?.playbackId) {
        val playing = flight ?: return@LaunchedEffect
        shown = playing
        progress.floatValue = 0f
        val durationNanos = playbackSeconds(playing.trajectory, reduceMotion) * NANOS_PER_SECOND
        val start = withFrameNanos { it }
        while (progress.floatValue < 1f) {
            val now = withFrameNanos { it }
            progress.floatValue = ((now - start) / durationNanos).toFloat().coerceIn(0f, 1f)
        }
        completed()
    }

    Spacer(
        modifier =
            modifier.testTag(RangeTestTags.SCENE).drawWithCache {
                val projection = RangeProjection(pose, size.width, size.height)
                val scene = RangeScene.build(projection, description)
                val current = shown
                val geometry =
                    current?.let {
                        FlightGeometry.build(
                            it.trajectory,
                            projection,
                            quality.tracerPointCount,
                        )
                    }
                val landing = geometry?.let { RangeScene.landingMarker(projection, it.landing) }.orEmpty()
                val sky =
                    Brush.verticalGradient(
                        0f to Sky,
                        1f to SkyHorizon,
                        startY = 0f,
                        endY = scene.horizonY.coerceAtLeast(1f),
                    )
                val labels =
                    scene.labels.map { label ->
                        val fontSize =
                            (label.scale * LABEL_HEIGHT_METERS).toSp().value.coerceIn(
                                MIN_LABEL_SP,
                                MAX_LABEL_SP,
                            )
                        label to textMeasurer.measure(label.text, labelStyle.copy(fontSize = fontSize.sp))
                    }
                onDrawBehind {
                    drawRect(sky)
                    for (polygon in scene.polygons) drawPath(polygon.path, polygon.color)
                    for ((label, layout) in labels) {
                        if (label.anchor.x.isNaN()) continue
                        val topLeft =
                            Offset(label.anchor.x - layout.size.width / 2f, label.anchor.y - layout.size.height)
                        drawText(layout, topLeft = topLeft)
                    }
                    if (geometry != null) {
                        val at = geometry.sampleAt(progress.floatValue)
                        if (progress.floatValue >= 1f) {
                            for (polygon in landing) drawPath(polygon.path, polygon.color)
                        }
                        drawFlight(geometry, at)
                    }
                }
            },
    )
}

/** The ball's shadow, the tracer up to [at] (a fractional sample index) and the ball on its tip. */
private fun DrawScope.drawFlight(
    geometry: FlightGeometry,
    at: Float,
) {
    val shadow = geometry.shadowAt(at)
    if (!shadow.x.isNaN()) {
        val radiusX = geometry.valueAt(geometry.shadowRadiiX, at)
        val radiusY = geometry.valueAt(geometry.shadowRadiiY, at)
        drawOval(
            color = ShadowColor,
            topLeft = Offset(shadow.x - radiusX, shadow.y - radiusY),
            size = Size(radiusX * 2, radiusY * 2),
        )
    }

    val whole = at.toInt()
    for (index in 0 until whole.coerceAtMost(geometry.segments)) {
        drawTracerSegment(geometry, index, geometry.pointAt(index + 1f).toOffset())
    }
    val tip = geometry.pointAt(at).toOffset()
    if (whole < geometry.segments && at > whole) drawTracerSegment(geometry, whole, tip)

    if (!tip.x.isNaN()) {
        drawCircle(color = Color.White, radius = geometry.valueAt(geometry.ballRadii, at), center = tip)
    }
}

private fun DrawScope.drawTracerSegment(
    geometry: FlightGeometry,
    index: Int,
    end: Offset,
) {
    val startX = geometry.xs[index]
    val startY = geometry.ys[index]
    if (startX.isNaN() || end.x.isNaN()) return
    drawLine(
        color = TracerColor,
        start = Offset(startX, startY),
        end = end,
        strokeWidth = (geometry.tracerWidths[index] + geometry.tracerWidths[index + 1]) / 2,
        cap = StrokeCap.Round,
    )
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val LABEL_HEIGHT_METERS = 2.2f
private const val MIN_LABEL_SP = 9f
private const val MAX_LABEL_SP = 16f

private val TracerColor =
    RangeTracerStyle.highVisibility.let { style ->
        Color(
            red = style.red.toFloat(),
            green = style.green.toFloat(),
            blue = style.blue.toFloat(),
            alpha = style.opacity.toFloat(),
        )
    }
private val ShadowColor = Color.Black.copy(alpha = 0.28f)
private val labelStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold)
