// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.RangeTracerStyle
import dev.openflight.companion.core.flight.Vec3

/**
 * Draws [scene] and the shown flight for one camera pose per frame (plan R7a). Holds the one
 * [RangeProjection], the flight's [FlightGeometry], the landing marker and the [TracerRibbon], and
 * re-projects only when the pose or the canvas size changed since the last
 * frame. Nothing in [draw] allocates.
 */
@Suppress("TooManyFunctions") // Small, allocation-free draw steps, one per layer.
internal class RangeRenderer(
    val scene: RangeScene,
) {
    private var projection: RangeProjection? = null
    private var projectedPose: RangeCameraPose? = null
    private var dirty = true

    private var geometry: FlightGeometry? = null
    private var geometryFor: ActiveFlight? = null
    private var landing: List<WorldPolygon> = emptyList()

    var labelLayouts: List<TextLayoutResult> = emptyList()
    var labelFontPixels = 1f

    private val tracer = TracerRibbon()

    /** Plan F8a1: the overlay's static trajectories, when overlaying. */
    private var overlay: OverlayGeometry? = null
    private var overlayFor: List<OverlayFlight>? = null

    /** Plan F8a1: the estimated roll-out, from the carry landing to the total dot, in scene space. */
    private var rollOutFor: Pair<RangeRollOut, FlightTrajectory>? = null
    private var rollOutStart: Vec3? = null
    private var rollOutEnd: Vec3? = null
    private val rollOutScreen = FloatArray(4)
    private var rollOutVisible = false

    /** The camera of the last drawn frame: gestures pan along the ground and taps select through it. */
    val currentProjection: RangeProjection? get() = projection

    fun resize(
        width: Float,
        height: Float,
        pose: RangeCameraPose,
    ) {
        val current = projection
        if (current == null) {
            projection = RangeProjection(pose, width, height)
            projectedPose = pose
        } else if (current.width != width || current.height != height) {
            current.update(projectedPose ?: pose, width, height)
        }
        dirty = true
    }

    fun setFlight(
        flight: ActiveFlight?,
        segments: Int,
    ) {
        val projection = projection ?: return
        if (flight === geometryFor) return
        geometryFor = flight
        geometry = flight?.let { FlightGeometry.build(it.trajectory, projection, segments) }
        landing = geometry?.let { RangeScene.landingMarker(it.landing) }.orEmpty()
        tracer.ensureCapacity(segments)
        dirty = true
    }

    /** Plan F8a1: the overlay to draw (built once per list of flights) and its highlighted shot. */
    fun setOverlay(
        flights: List<OverlayFlight>,
        selectedId: String?,
    ) {
        if (flights !== overlayFor) {
            overlayFor = flights
            overlay = if (flights.isEmpty()) null else OverlayGeometry(flights)
            dirty = true
        }
        if (overlay?.select(selectedId) == true) dirty = true
    }

    /** Plan F8a1: the roll-out marker for [rollOut], continuing [trajectory]'s landing. */
    fun setRollOut(
        rollOut: RangeRollOut?,
        trajectory: FlightTrajectory?,
    ) {
        val key = if (rollOut != null && trajectory != null) rollOut to trajectory else null
        if (key == rollOutFor) return
        rollOutFor = key
        val landingPoint = trajectory?.points?.lastOrNull()?.positionMeters
        rollOutStart = landingPoint?.let { RangeProjection.flightToScene(it).copy(y = 0.0) }
        rollOutEnd =
            if (rollOut != null && trajectory != null) {
                RangeProjection.flightToScene(rollOutEnd(trajectory, rollOut.rollYards)).copy(y = 0.0)
            } else {
                null
            }
        dirty = true
    }

    @Suppress("LongParameterList") // One frame's camera, playback and label inputs.
    fun draw(
        drawScope: DrawScope,
        pose: RangeCameraPose,
        progress: Float,
        labelHeightMeters: Float,
        minLabelPixels: Float,
        rollOutLabel: TextLayoutResult? = null,
    ) {
        val projection = projection ?: return
        if (dirty || pose != projectedPose) {
            projection.update(pose, projection.width, projection.height)
            projectedPose = pose
            scene.project(projection)
            geometry?.reproject(projection)
            overlay?.reproject(projection)
            for (index in landing.indices) landing[index].project(projection, scene.scratch)
            projectRollOut(projection)
            dirty = false
        }
        with(drawScope) {
            drawBackdrop(projection)
            drawPolygons(scene.polygons)
            drawTrees()
            drawLabels(labelHeightMeters, minLabelPixels)
            overlay?.let { drawOverlay(it) }
            val geometry = geometry
            if (geometry == null) {
                if (overlay != null) drawRollOut(rollOutLabel)
                return
            }
            if (progress >= 1f) {
                drawPolygons(landing)
                drawRollOut(rollOutLabel)
            }
            drawFlight(geometry, geometry.sampleAt(progress))
        }
    }

    private fun projectRollOut(projection: RangeProjection) {
        val start = rollOutStart
        val end = rollOutEnd
        rollOutVisible =
            start != null &&
            end != null &&
            projection.projectInto(start.x, start.y, start.z, rollOutScreen, START_X) &&
            projection.projectInto(end.x, end.y, end.z, rollOutScreen, END_X)
    }

    /** Strokes for the overlay, rebuilt only when the density changes (so a frame allocates nothing). */
    private var overlayStroke = Stroke()
    private var selectedStroke = Stroke()

    private fun DrawScope.drawOverlay(overlay: OverlayGeometry) {
        val width = OVERLAY_STROKE_DP.dp.toPx()
        if (overlayStroke.width != width) {
            overlayStroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            selectedStroke =
                Stroke(width = SELECTED_STROKE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        val stroke = overlayStroke
        for (index in overlay.groupPaths.indices) {
            drawPath(overlay.groupPaths[index], overlay.groupColors[index], style = stroke)
        }
        val dotRadius = OVERLAY_DOT_DP.dp.toPx()
        for (index in overlay.flights.indices) {
            val x = overlay.landingXs[index]
            if (x.isNaN()) continue
            drawCircle(overlay.landingColors[index], radius = dotRadius, center = Offset(x, overlay.landingYs[index]))
        }
        val selected = overlay.selectedIndex
        if (selected >= 0) {
            drawPath(overlay.selectedPath, SelectedColor, style = selectedStroke)
            val x = overlay.landingXs[selected]
            if (!x.isNaN()) {
                drawCircle(SelectedColor, radius = dotRadius * 2, center = Offset(x, overlay.landingYs[selected]))
            }
        }
    }

    /** The carry → total segment, the total dot and its "est." label (plan F2/F8a1). */
    private fun DrawScope.drawRollOut(label: TextLayoutResult?) {
        if (!rollOutVisible) return
        val start = Offset(rollOutScreen[START_X], rollOutScreen[START_Y])
        val end = Offset(rollOutScreen[END_X], rollOutScreen[END_Y])
        drawLine(RollOutColor, start, end, strokeWidth = ROLL_OUT_STROKE_DP.dp.toPx(), pathEffect = RollOutDash)
        drawCircle(RollOutColor, radius = ROLL_OUT_DOT_DP.dp.toPx(), center = end)
        if (label != null) {
            drawText(
                label,
                topLeft =
                    Offset(
                        end.x - label.size.width / 2f,
                        end.y - label.size.height - ROLL_OUT_DOT_DP.dp.toPx() * 2,
                    ),
            )
        }
    }

    /** Sky down to the horizon, distant ground below it (the ground plane is finite). */
    private fun DrawScope.drawBackdrop(projection: RangeProjection) {
        val horizon = scene.horizonY.coerceIn(0f, projection.height)
        if (horizon < projection.height) {
            drawRect(Ground, topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
        }
        if (horizon > 0f) {
            scale(scaleX = 1f, scaleY = horizon, pivot = Offset.Zero) {
                drawRect(SkyGradient, size = Size(size.width, 1f))
            }
        }
    }

    private fun DrawScope.drawPolygons(polygons: List<WorldPolygon>) {
        for (index in polygons.indices) {
            val polygon = polygons[index]
            if (polygon.visible) drawPath(polygon.path, polygon.color)
        }
    }

    private fun DrawScope.drawTrees() {
        val order = scene.treeOrder
        for (position in order.indices) {
            val tree = scene.trees[order[position]]
            if (tree.trunk.visible) drawPath(tree.trunk.path, tree.trunk.color)
            drawSphere(tree.crown)
            drawSphere(tree.crownTop)
        }
    }

    private fun DrawScope.drawSphere(sphere: WorldSphere) {
        if (sphere.visible) {
            drawCircle(sphere.color, radius = sphere.screenRadius, center = Offset(sphere.screenX, sphere.screenY))
        }
    }

    private fun DrawScope.drawLabels(
        labelHeightMeters: Float,
        minLabelPixels: Float,
    ) {
        val labels = scene.labels
        for (index in labels.indices) {
            val label = labels[index]
            if (!label.visible || index >= labelLayouts.size) continue
            val layout = labelLayouts[index]
            val fontPixels = (label.scale * labelHeightMeters).coerceIn(minLabelPixels, labelFontPixels)
            val factor = fontPixels / labelFontPixels
            val anchor = Offset(label.anchorX, label.anchorY)
            scale(factor, pivot = anchor) {
                drawText(
                    layout,
                    topLeft = Offset(label.anchorX - layout.size.width / 2f, label.anchorY - layout.size.height),
                )
            }
        }
    }

    /** The ball's shadow, the tracer up to [at] (a fractional sample index) and the ball on its tip. */
    private fun DrawScope.drawFlight(
        geometry: FlightGeometry,
        at: Float,
    ) {
        val shadowX = geometry.valueAt(geometry.shadowXs, at)
        val shadowY = geometry.valueAt(geometry.shadowYs, at)
        if (!shadowX.isNaN() && !shadowY.isNaN()) {
            val radiusX = geometry.valueAt(geometry.shadowRadiiX, at)
            val radiusY = geometry.valueAt(geometry.shadowRadiiY, at)
            drawOval(
                color = ShadowColor,
                topLeft = Offset(shadowX - radiusX, shadowY - radiusY),
                size = Size(radiusX * 2, radiusY * 2),
            )
        }

        tracer.build(geometry, at)
        drawPath(tracer.path, TracerColor)

        val tipX = tracer.tipX
        val tipY = tracer.tipY
        if (!tipX.isNaN()) {
            drawCircle(
                color = Color.White,
                radius = geometry.valueAt(geometry.ballRadii, at),
                center = Offset(tipX, tipY),
            )
        }
    }
}

private val SkyGradient = Brush.verticalGradient(0f to Sky, 1f to SkyHorizon, startY = 0f, endY = 1f)
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
private val SelectedColor = Color(0xFFD4AF37)
private val RollOutColor = Color.White.copy(alpha = 0.9f)
private val RollOutDash = PathEffect.dashPathEffect(floatArrayOf(ROLL_OUT_DASH_PX, ROLL_OUT_GAP_PX))
private const val OVERLAY_STROKE_DP = 2f
private const val SELECTED_STROKE_DP = 4f
private const val OVERLAY_DOT_DP = 3f
private const val ROLL_OUT_STROKE_DP = 2.5f
private const val ROLL_OUT_DOT_DP = 5f
private const val ROLL_OUT_DASH_PX = 10f

/** Indices into the roll-out's projected start and end points. */
private const val START_X = 0
private const val START_Y = 1
private const val END_X = 2
private const val END_Y = 3
private const val ROLL_OUT_GAP_PX = 8f
