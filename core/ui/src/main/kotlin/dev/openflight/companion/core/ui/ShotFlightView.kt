// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.rememberOfReduceMotion
import dev.openflight.companion.core.flight.FlightPathProjector
import dev.openflight.companion.core.flight.FlightView
import dev.openflight.companion.core.flight.ProjectedFlight
import dev.openflight.companion.core.flight.ScreenPoint
import dev.openflight.companion.core.flight.ShotFlightPath
import dev.openflight.companion.core.insights.ShotDetailTestTags

/**
 * The shot detail's mini ball flight: the shot drawn [FlightView.BEHIND] the golfer (drag sideways
 * to orbit), from the [FlightView.TOP] or from the [FlightView.SIDE]. It flies once when shown and
 * again on a tap; under reduced motion it shows the landed flight, still.
 *
 * The geometry comes from `core:flight`'s [FlightPathProjector], the same one SwiftUI draws, so both
 * platforms show the same picture.
 */
@Composable
fun ShotFlightView(
    flight: ShotFlightPath,
    contentDescription: String,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberOfReduceMotion(),
) {
    var viewName by rememberSaveable { mutableStateOf(FlightView.BEHIND.name) }
    val view = FlightView.valueOf(viewName)
    var orbit by rememberSaveable { mutableFloatStateOf(0f) }
    var replays by remember { mutableIntStateOf(0) }
    val progress = remember(flight) { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(flight, replays, reduceMotion) {
        if (reduceMotion) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            val millis = (flight.flightTimeSeconds * PLAYBACK_SPEED_UP).toInt().coerceIn(MIN_MILLIS, MAX_MILLIS)
            progress.animateTo(1f, tween(durationMillis = millis, easing = LinearEasing))
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
        OfSegmentedPicker(
            options = FlightView.entries.map { it.label },
            selected = view.label,
            onSelect = { label -> viewName = FlightView.entries.first { it.label == label }.name },
            modifier = Modifier.fillMaxWidth().testTag(ShotDetailTestTags.VIEW_PICKER),
        )
        FlightCanvas(
            flight = flight,
            view = view,
            orbit = orbit,
            progress = progress.value,
            onOrbit = { orbit = clampOrbit(orbit + it) },
            onReplay = { replays++ },
            contentDescription = contentDescription,
        )
        OfText(
            text = if (view == FlightView.BEHIND) "Drag to orbit · tap to replay" else "Tap to replay",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamMuted,
        )
    }
}

@Suppress("LongParameterList") // One drawing's inputs; split up they'd only travel together.
@Composable
private fun FlightCanvas(
    flight: ShotFlightPath,
    view: FlightView,
    orbit: Float,
    progress: Float,
    onOrbit: (Float) -> Unit,
    onReplay: () -> Unit,
    contentDescription: String,
) {
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(CANVAS_ASPECT)
                .clip(shape)
                .background(OfColorTokens.BgDeep, shape)
                .testTag(ShotDetailTestTags.FLIGHT)
                .semantics {
                    this.contentDescription = contentDescription
                    onClick(label = "Replay the flight") {
                        onReplay()
                        true
                    }
                }.pointerInput(view) {
                    if (view == FlightView.BEHIND) {
                        detectHorizontalDragGestures { _, drag -> onOrbit(drag * ORBIT_DEGREES_PER_PX) }
                    }
                }.pointerInput(Unit) { detectTapGestures(onTap = { onReplay() }) },
    ) {
        val projected =
            FlightPathProjector.project(
                flight,
                view,
                size.width,
                size.height,
                orbit,
                PADDING.dp.toPx(),
                minMarkerGap = MARKER_GAP.dp.toPx(),
            )
        drawGround(projected)
        val labelStyle = TextStyle(color = OfColorTokens.CreamMuted, fontSize = 10.sp)
        projected.markers.forEach { marker ->
            val label = measurer.measure("${marker.yards}", labelStyle)
            val at = marker.position.offset()
            drawCircle(OfColorTokens.CreamMuted, radius = 2.dp.toPx(), center = at)
            drawText(label, topLeft = at + Offset(4.dp.toPx(), -label.size.height / 2f))
        }
        drawFlight(projected, progress)
    }
}

private fun clampOrbit(degrees: Float): Float =
    degrees.coerceIn(-FlightPathProjector.MAX_ORBIT_DEGREES, FlightPathProjector.MAX_ORBIT_DEGREES)

private fun DrawScope.drawGround(projected: ProjectedFlight) {
    val thin = 1.dp.toPx()
    projected.guides.forEach { guide ->
        drawLine(OfColorTokens.BgHover, guide.first().offset(), guide.last().offset(), strokeWidth = thin)
    }
    drawLine(
        OfColorTokens.CreamMuted,
        projected.targetLine.first().offset(),
        projected.targetLine.last().offset(),
        strokeWidth = thin,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
    )
}

private fun DrawScope.drawFlight(
    projected: ProjectedFlight,
    progress: Float,
) {
    val shown = (progress * (projected.path.size - 1)).toInt()
    val ball = projected.ballAt(progress)
    val trail =
        Path().apply {
            projected.path.first().let { moveTo(it.x, it.y) }
            projected.path
                .take(shown + 1)
                .drop(1)
                .forEach { lineTo(it.x, it.y) }
            lineTo(ball.x, ball.y)
        }
    drawPath(
        trail,
        OfColorTokens.Gold,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    if (progress >= 1f && projected.roll.size == 2) {
        drawLine(
            OfColorTokens.GoldDim,
            projected.roll.first().offset(),
            projected.roll.last().offset(),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
        drawCircle(OfColorTokens.GoldDim, radius = 3.dp.toPx(), center = projected.roll.last().offset())
    }
    drawCircle(OfColorTokens.Cream, radius = 3.dp.toPx(), center = projected.path.first().offset())
    drawCircle(OfColorTokens.Cream, radius = BALL_RADIUS.dp.toPx(), center = ball.offset())
}

private fun ScreenPoint.offset(): Offset = Offset(x, y)

private const val CANVAS_ASPECT = 1.6f
private const val PADDING = 20
private const val BALL_RADIUS = 5

/** Distance markers closer than this are dropped, so their labels never overlap. */
private const val MARKER_GAP = 22
private const val ORBIT_DEGREES_PER_PX = 0.25f

/** Plays the flight about three times faster than real time, kept between 1.2 and 2.5 s. */
private const val PLAYBACK_SPEED_UP = 350.0
private const val MIN_MILLIS = 1_200
private const val MAX_MILLIS = 2_500

@Preview
@Composable
private fun ShotFlightViewPreview() {
    OfTheme {
        previewShotDetail().flight?.let {
            ShotFlightView(
                it,
                "A push fade",
                modifier = Modifier.background(OfColorTokens.BgElevated),
                reduceMotion = true,
            )
        }
    }
}
