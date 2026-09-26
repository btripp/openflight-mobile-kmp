// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.designsystem.OfClubPalette
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 2.5D range: the scene, the one tracer, the ball, its shadow and the landing marker, all
 * drawn on a `Canvas` through [RangeProjection] (the RealityKit scene in the reference).
 *
 * A new [ActiveFlight.playbackId] starts a playback; it runs on the frame clock for
 * [playbackSeconds] (divided by [ActiveFlight.speed] in replay) and then calls
 * [onFlightCompleted]. After that the frame clock keeps running for [RangeCameraRig.settleSeconds]
 * so the follow camera can settle over the landing spot. A `null` [flight] (landed and dwelt, or
 * suspended) freezes the last flight where it is, like the reference's `suspend()`, until the
 * next playback replaces it: only one tracer is ever drawn.
 *
 * Every frame asks [RangeCameraRig] for the pose of [cameraMode] (plan R7a). The scene and the
 * flight are kept in world space and re-projected only when the pose or the canvas changes: every
 * frame while the follow camera moves, once per size for the fixed camera. Re-projecting rewrites
 * the same `Path`s and float arrays, so a frame allocates nothing but the planner's small pose.
 *
 * Plan F8a1 view controls: a pinch zooms, a two-finger drag pans along the ground, a one-finger
 * horizontal drag orbits, and a double tap resets. While [view] isn't the identity the follow
 * camera is suspended and the fixed tee camera is transformed instead. Each gesture reports the
 * whole new [ViewTransform] through [onViewChanged]. In the overlay, [overlay]'s static
 * trajectories are drawn (re-projected only when the camera moves) and a tap near a landing
 * selects it through [onSelectLanding]. [rollOut] draws the estimated roll-out to the total dot
 * once the ball has landed (or for the selected overlay shot).
 */
@Suppress("LongParameterList") // The scene's state plus its gesture callbacks.
@Composable
fun RangeCanvas(
    flight: ActiveFlight?,
    cameraMode: RangeCameraMode,
    reduceMotion: Boolean,
    onFlightCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    view: ViewTransform = ViewTransform.IDENTITY,
    rollOut: RangeRollOut? = null,
    overlay: List<OverlayFlight> = emptyList(),
    overlayMode: Boolean = false,
    selectedOverlayId: String? = null,
    onViewChanged: (ViewTransform) -> Unit = {},
    onResetView: () -> Unit = {},
    onSelectLanding: (String) -> Unit = {},
) {
    var shown by remember { mutableStateOf<ActiveFlight?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }
    val landedSeconds = remember { mutableFloatStateOf(0f) }
    val mode by rememberUpdatedState(cameraMode)
    val completed by rememberUpdatedState(onFlightCompleted)
    val currentView = rememberUpdatedState(view)
    val overlayState = rememberUpdatedState(overlay)
    val currentOverlay by overlayState
    val selectedState = rememberUpdatedState(selectedOverlayId)
    val rollOutState = rememberUpdatedState(rollOut)
    val viewChanged by rememberUpdatedState(onViewChanged)
    val resetView by rememberUpdatedState(onResetView)
    val selectLanding by rememberUpdatedState(onSelectLanding)
    // Plan F8c1: the shared palette and sizes; F8a2 makes the theme selectable.
    val style = RangeTheme.DAY.style
    val rig = remember { RangeCameraRig() }
    val renderer =
        remember(style) { RangeRenderer(RangeFrame(style, OfClubPalette.colors.size, ::ComposePathSink)) }
    val textMeasurer = rememberTextMeasurer()

    // Entering the overlay drops the frozen tracer: the overlay's own trajectories replace it.
    LaunchedEffect(overlayMode) {
        if (overlayMode && flight == null) shown = null
    }

    LaunchedEffect(flight?.playbackId) {
        val playing = flight
        if (playing != null) {
            shown = playing
            progress.floatValue = 0f
            landedSeconds.floatValue = 0f
            val durationNanos =
                playbackSeconds(playing.trajectory, reduceMotion) / playing.speed.coerceAtLeast(MIN_SPEED) *
                    NANOS_PER_SECOND
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

    // One remembered block that reads everything through State: a gesture recomposes this canvas
    // every frame, and a fresh lambda would rebuild the cache (and re-measure the labels) each time.
    val drawCache: CacheDrawScope.() -> DrawResult =
        remember(renderer, textMeasurer) {
            {
                val frame = renderer.frame
                frame.resize(size.width, size.height, rig.fixedPose)
                frame.setFlight(shown, RangeFrame.QUALITY.tracerPointCount)
                val flights = overlayState.value
                val selected = selectedState.value
                val estimate = rollOutState.value
                frame.setOverlay(flights, selected)
                val rollOutTrajectory = shown?.trajectory ?: flights.firstOrNull { it.shotId == selected }?.trajectory
                frame.setRollOut(estimate, rollOutTrajectory)
                val labelColor = style.label.toColor()
                val rollOutLabel =
                    estimate?.let {
                        textMeasurer.measure(
                            it.totalLabel,
                            TextStyle(
                                color = labelColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = style.rollOutLabelSize.sp,
                            ),
                        )
                    }
                renderer.labelLayouts =
                    renderer.scene.labels.map { label ->
                        textMeasurer.measure(
                            label.text,
                            TextStyle(
                                color = labelColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = style.maxLabelSize.sp,
                            ),
                        )
                    }
                renderer.labelFontPixels = style.maxLabelSize.sp.toPx()
                onDrawBehind {
                    val current = shown
                    val transform = currentView.value
                    val pose =
                        if (transform.isIdentity) {
                            rig.pose(
                                mode,
                                current?.trajectory,
                                progress.floatValue.toDouble(),
                                landedSeconds.floatValue.toDouble(),
                            )
                        } else {
                            // Plan F8a: the follow camera is suspended while the user has moved the view.
                            transform.applyTo(rig.fixedPose)
                        }
                    renderer.draw(
                        this,
                        pose,
                        progress.floatValue,
                        style.labelHeightMeters,
                        style.minLabelSize.sp.toPx(),
                        rollOutLabel,
                    )
                }
            }
        }

    Spacer(
        modifier =
            modifier
                .testTag(RangeTestTags.SCENE)
                .semantics { stateDescription = viewDescription(view) }
                .rangeViewGestures(
                    renderer = renderer,
                    view = currentView,
                    overlay = { currentOverlay },
                    onViewChanged = { viewChanged(it) },
                    onResetView = { resetView() },
                    onSelectLanding = { selectLanding(it) },
                ).drawWithCache(drawCache),
    )
}

/**
 * Plan F8a1 view gestures on the scene: double tap resets, a tap near an overlay landing selects
 * it, two fingers pinch-zoom and pan along the ground, one finger drags to orbit. Each gesture
 * reports the whole new [ViewTransform].
 */
@Suppress("LongParameterList") // The camera, the view and one callback per gesture.
private fun Modifier.rangeViewGestures(
    renderer: RangeRenderer,
    view: State<ViewTransform>,
    overlay: () -> List<OverlayFlight>,
    onViewChanged: (ViewTransform) -> Unit,
    onResetView: () -> Unit,
    onSelectLanding: (String) -> Unit,
): Modifier =
    pointerInput(Unit) {
        detectTapGestures(
            onDoubleTap = { onResetView() },
            onTap = { tap ->
                val flights = overlay()
                val projection = renderer.currentProjection
                if (flights.isNotEmpty() && projection != null) {
                    nearestOverlayLanding(projection, flights, tap.x, tap.y, TAP_REACH_DP.dp.toPx())
                        ?.let(onSelectLanding)
                }
            },
        )
    }.pointerInput(Unit) {
        // Pinch/pan with two fingers, orbit with one; taps pass through untouched.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // Accumulated locally: the view model's state may lag a pointer event behind.
            var local = view.value
            var dragX = 0f
            var orbiting = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                val next =
                    when {
                        pressed >= 2 -> {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            local.pinchedAndPanned(event, renderer.currentProjection)
                        }

                        pressed == 1 -> {
                            val change = event.changes.first { it.pressed }
                            val dx = change.position.x - change.previousPosition.x
                            dragX += dx
                            orbiting = orbiting || abs(dragX) > viewConfiguration.touchSlop
                            if (orbiting && size.width > 0) {
                                change.consume()
                                local.orbitedBy(dx / size.width * ViewTransform.ORBIT_DEGREES_PER_WIDTH)
                            } else {
                                local
                            }
                        }

                        else -> {
                            local
                        }
                    }
                if (next != local) {
                    local = next
                    onViewChanged(next)
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** A two-finger step: the pinch's zoom, then its centroid's move as a pan along the ground. */
private fun ViewTransform.pinchedAndPanned(
    event: PointerEvent,
    projection: RangeProjection?,
): ViewTransform {
    val zoomed = zoomedBy(event.calculateZoom().toDouble())
    val from = event.calculateCentroid(useCurrent = false)
    val to = event.calculateCentroid(useCurrent = true)
    return if (projection != null && from.isSpecified && to.isSpecified) {
        zoomed.pannedAlongGround(projection, from.x, from.y, to.x, to.y)
    } else {
        zoomed
    }
}

/** The scene's accessibility state: "Default view", or the zoom and orbit the user chose. */
private fun viewDescription(view: ViewTransform): String =
    if (view.isIdentity) {
        "Default view"
    } else {
        "Zoom ${(view.zoom * PERCENT).roundToInt()} percent, orbit ${view.orbitYawDegrees.roundToInt()} degrees"
    }

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val TAP_REACH_DP = 40
private const val MIN_SPEED = 0.1
private const val PERCENT = 100
