// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.RangeTracerStyle

/**
 * Draws [scene] and the shown flight for one camera pose per frame (plan R7a). Holds the one
 * [RangeProjection], the flight's [FlightGeometry], the landing marker and the [TracerRibbon], and
 * re-projects only when the pose or the canvas size changed since the last
 * frame. Nothing in [draw] allocates.
 */
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

    fun draw(
        drawScope: DrawScope,
        pose: RangeCameraPose,
        progress: Float,
        labelHeightMeters: Float,
        minLabelPixels: Float,
    ) {
        val projection = projection ?: return
        if (dirty || pose != projectedPose) {
            projection.update(pose, projection.width, projection.height)
            projectedPose = pose
            scene.project(projection)
            geometry?.reproject(projection)
            for (index in landing.indices) landing[index].project(projection, scene.scratch)
            dirty = false
        }
        with(drawScope) {
            drawBackdrop(projection)
            drawPolygons(scene.polygons)
            drawTrees()
            drawLabels(labelHeightMeters, minLabelPixels)
            val geometry = geometry ?: return
            if (progress >= 1f) drawPolygons(landing)
            drawFlight(geometry, geometry.sampleAt(progress))
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
