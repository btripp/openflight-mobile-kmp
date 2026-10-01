// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.flight.RangeCameraPose

/**
 * The 2.5D range: the scene, the one tracer, the ball, its shadow and the landing marker, all
 * drawn on a `Canvas` through [RangeProjection] (the RealityKit scene in the reference).
 *
 * A new [ActiveFlight.playbackId] starts a playback; it runs on the frame clock for
 * [playbackSeconds] (divided by [ActiveFlight.speed] in replay) and then calls
 * [onFlightComplete]. After that the frame clock keeps running for [RangeCameraRig.settleSeconds]
 * so the follow camera can settle over the landing spot. A `null` [flight] (landed and dwelt, or
 * suspended) freezes the last flight where it is, like the reference's `suspend()`, until the
 * next playback replaces it: only one tracer is ever drawn.
 *
 * Every frame asks [RangeCameraRig] for the pose of [cameraMode] (plan R7a). The scene and the
 * flight are kept in world space and re-projected only when the pose or the canvas changes: every
 * frame while the follow camera moves, once per size for the fixed camera. Re-projecting rewrites
 * the same `Path`s and float arrays, so a frame allocates nothing but the planner's small pose.
 *
 * Plan F8a1 view controls, made map-like by plan F8a2p (the mapping is [ViewTransform]'s):
 * - one finger drags the range, and the ground under it follows;
 * - a pinch zooms about its centre;
 * - two fingers twisting, or dragging sideways, orbit;
 * - a double tap resets.
 *
 * Zoom is pinch-only on screen; TalkBack gets "Zoom in" and "Zoom out" custom actions instead
 * ([ZOOM_IN_ACTION], [ZOOM_OUT_ACTION]), a step of [ViewTransform.ZOOM_STEP] each.
 *
 * They work in every mode (live, replay and overlay). While [view] isn't the identity the follow
 * camera is suspended and the fixed tee camera is transformed instead. Each gesture reports the
 * whole new [ViewTransform] through [onViewChange]. In the overlay, [overlay]'s static
 * trajectories are drawn (re-projected only when the camera moves) and a tap near a landing
 * selects it through [onSelectLanding]. [rollOut] draws the estimated roll-out to the total dot
 * once the ball has landed (or for the selected overlay shot).
 *
 * Plan F8a2p: [obstructions] are the overlaid UI's rectangles in this canvas's pixels (`left, top,
 * right, bottom` each); the yardage labels under them are hidden and the markers faded.
 *
 * Plan F8a2t: [trail] picks the shot trail's style, its landing effect (static while
 * [reduceMotion]) and the earlier live trails to keep; they're drawn by the shared [ShotTrail].
 *
 * Plan F8a2a: [theme] picks the palette (the renderer is rebuilt when it changes). Debug
 * [freezeProgress] (the `range_freeze_progress` launch extra, iOS's `--range-freeze-progress`)
 * holds every flight at that playback progress for screenshots; at 1 the flight lands and the
 * follow camera is shown fully settled.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod") // The scene's state plus its gesture callbacks.
@Composable
fun RangeCanvas(
    flight: ActiveFlight?,
    cameraMode: RangeCameraMode,
    reduceMotion: Boolean,
    onFlightComplete: () -> Unit,
    modifier: Modifier = Modifier,
    view: ViewTransform = ViewTransform.IDENTITY,
    rollOut: RangeRollOut? = null,
    overlay: List<OverlayFlight> = emptyList(),
    overlayMode: Boolean = false,
    selectedOverlayId: String? = null,
    onViewChange: (ViewTransform) -> Unit = {},
    onResetView: () -> Unit = {},
    onSelectLanding: (String) -> Unit = {},
    theme: RangeTheme = RangeTheme.DAY,
    freezeProgress: Float? = null,
    obstructions: FloatArray = NO_OBSTRUCTIONS,
    trail: RangeTrailState = RangeTrailState(),
    interactive: Boolean = true,
    sceneTag: String = RangeTestTags.SCENE,
) {
    var shown by remember { mutableStateOf<ActiveFlight?>(null) }
    val progress = remember { mutableFloatStateOf(0f) }
    val landedSeconds = remember { mutableFloatStateOf(0f) }
    val mode by rememberUpdatedState(cameraMode)
    val completed by rememberUpdatedState(onFlightComplete)
    val currentView = rememberUpdatedState(view)
    val overlayState = rememberUpdatedState(overlay)
    val currentOverlay by overlayState
    val selectedState = rememberUpdatedState(selectedOverlayId)
    val rollOutState = rememberUpdatedState(rollOut)
    val viewChanged by rememberUpdatedState(onViewChange)
    val resetView by rememberUpdatedState(onResetView)
    val selectLanding by rememberUpdatedState(onSelectLanding)
    val obstructionState = rememberUpdatedState(obstructions)
    val trailState = rememberUpdatedState(trail)
    val reduceMotionState = rememberUpdatedState(reduceMotion)
    // Plan F8c1: the shared palette and sizes; plan F8a2a: the user's theme.
    val style = theme.style
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
            if (freezeProgress != null) {
                // Debug: hold the flight (or, at 1, its fully settled landing) for screenshots.
                progress.floatValue = freezeProgress.coerceIn(0f, 1f)
                if (freezeProgress >= 1f) {
                    landedSeconds.floatValue = rig.settleSeconds.toFloat()
                    completed()
                }
                return@LaunchedEffect
            }
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
                frame.setLabelPixels(style.minLabelSize.sp.toPx(), renderer.labelFontPixels)
                onDrawBehind {
                    frame.setObstructions(obstructionState.value)
                    // Plan F8a2t: the trail style, landing effect and kept earlier trails.
                    frame.setTrail(trailState.value, staticEffects = reduceMotionState.value)
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
                        landedSeconds.floatValue,
                    )
                }
            }
        }

    // Plan F8a2t: a non-interactive canvas (the Settings preview) takes no gestures, so it scrolls.
    val gestures =
        if (interactive) {
            Modifier.rangeViewGestures(
                renderer = renderer,
                base = rig.fixedPose,
                view = currentView,
                overlay = { currentOverlay },
                onViewChange = { viewChanged(it) },
                onResetView = { resetView() },
                onSelectLanding = { selectLanding(it) },
            )
        } else {
            Modifier
        }
    Spacer(
        modifier =
            modifier
                .testTag(sceneTag)
                .semantics {
                    if (!interactive) return@semantics
                    stateDescription = view.description
                    // Plan F8a2p: zoom without the pinch, for TalkBack (zoom is pinch-only on screen).
                    customActions =
                        listOf(
                            CustomAccessibilityAction(ZOOM_IN_ACTION) {
                                view.canZoomIn.also { if (it) viewChanged(view.zoomedBySteps(1)) }
                            },
                            CustomAccessibilityAction(ZOOM_OUT_ACTION) {
                                view.canZoomOut.also { if (it) viewChanged(view.zoomedBySteps(-1)) }
                            },
                        )
                }.then(gestures)
                .drawWithCache(drawCache),
    )
}

/**
 * The range's view gestures (plan F8a1, made map-like by plan F8a2p): a double tap resets, a tap
 * near an overlay landing selects it, one finger drags the range along the ground, and two fingers
 * pinch-zoom about their centre and orbit by twisting or dragging sideways. Each gesture reports
 * the whole new [ViewTransform], built from [base] (the tee camera) on this canvas.
 */
@Suppress("LongParameterList") // The camera, the view and one callback per gesture.
private fun Modifier.rangeViewGestures(
    renderer: RangeRenderer,
    base: RangeCameraPose,
    view: State<ViewTransform>,
    overlay: () -> List<OverlayFlight>,
    onViewChange: (ViewTransform) -> Unit,
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
    }.pointerInput(base) {
        // Taps pass through untouched: a finger only drags once it has moved past the touch slop.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Accumulated locally: the view model's state may lag a pointer event behind.
            var local = view.value
            var dragging = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                val next =
                    when {
                        width <= 0f || height <= 0f -> {
                            local
                        }

                        pressed >= 2 -> {
                            dragging = true
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            local.pinchedAndTwisted(event, base, width, height)
                        }

                        pressed == 1 -> {
                            val change = event.changes.first { it.pressed }
                            dragging =
                                dragging ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            if (dragging) local.dragged(change, base, width, height) else local
                        }

                        else -> {
                            local
                        }
                    }
                if (next != local) {
                    local = next
                    onViewChange(next)
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** A one-finger step: the ground under the finger follows it (consumed, so it isn't a tap). */
private fun ViewTransform.dragged(
    change: PointerInputChange,
    base: RangeCameraPose,
    width: Float,
    height: Float,
): ViewTransform {
    change.consume()
    return draggedAlongGround(
        base,
        width,
        height,
        change.previousPosition.x,
        change.previousPosition.y,
        change.position.x,
        change.position.y,
    )
}

/**
 * A two-finger step: the pinch zooms about the fingers' centre, and a twist (clockwise orbits
 * right) or a sideways drag of both fingers orbits.
 */
private fun ViewTransform.pinchedAndTwisted(
    event: PointerEvent,
    base: RangeCameraPose,
    width: Float,
    height: Float,
): ViewTransform {
    val from = event.calculateCentroid(useCurrent = false)
    val to = event.calculateCentroid(useCurrent = true)
    if (!from.isSpecified || !to.isSpecified) return this
    val zoomed = zoomedAbout(event.calculateZoom().toDouble(), base, width, height, to.x, to.y)
    val orbit = event.calculateRotation() + (to.x - from.x) / width * ViewTransform.ORBIT_DEGREES_PER_WIDTH
    return if (orbit == 0.0) zoomed else zoomed.orbitedBy(orbit)
}

private val NO_OBSTRUCTIONS = FloatArray(0)

/** Plan F8a2p: the scene's accessibility actions (TalkBack custom actions; VoiceOver's on iOS). */
internal const val ZOOM_IN_ACTION = "Zoom in"
internal const val ZOOM_OUT_ACTION = "Zoom out"
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val TAP_REACH_DP = 40
private const val MIN_SPEED = 0.1
