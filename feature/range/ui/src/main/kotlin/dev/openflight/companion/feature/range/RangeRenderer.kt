// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.flight.RangeCameraPose

/**
 * Paints one [RangeFrame] per frame (plan R7a/F8c1): the shared frame keeps the scene, the flight,
 * the landing marker, the tracer, the overlay and the roll-out projected; this only draws them, in
 * the frame's documented order, with the colours and sizes of [RangeFrame.style]. Nothing in
 * [draw] allocates.
 */
internal class RangeRenderer(
    val frame: RangeFrame<ComposePathSink>,
) {
    val scene: RangeScene<ComposePathSink> get() = frame.scene
    private val style = frame.style

    var labelLayouts: List<TextLayoutResult> = emptyList()
    var labelFontPixels = 1f

    /** The camera of the last drawn frame: gestures pan along the ground and taps select through it. */
    val currentProjection: RangeProjection? get() = frame.projection

    private val skyGradient = style.sky.toVerticalBrush(startY = 0f, endY = 1f)

    /** Plan F8a2p: the rough and fairway are flat; the haze overlay and the soft stripes are gradients. */
    private val groundColor = style.ground.toColor()
    private val fairwayColor = style.fairway.toColor()
    private val haze = MovableLinearGradient(style.haze.overlayStops)
    private val stripeGradients = scene.stripes.map { MovableLinearGradient(style.stripeGradient) }

    /** Plan F8a2a: the sun's glow on a unit circle at the origin, moved and scaled onto the sun per frame. */
    private val sunGlow = style.sun.glow.toUnitRadialBrush()
    private val sunDiscColor = style.sun.disc.toColor()
    private val ridgeColors = scene.sky.ridges.map { it.color.toColor() }
    private val ballColor = style.ball.toColor()
    private val shadowColor = style.shadow.toColor()
    private val selectedColor = style.overlaySelected.toColor()
    private val rollOutColor = style.rollOut.toColor()
    private val rollOutDash = PathEffect.dashPathEffect(floatArrayOf(style.rollOutDashPixels, style.rollOutGapPixels))

    /** The overlay's club colours (at the overlay alpha for the strokes), resolved once per overlay. */
    private var overlayColorsFor: OverlayGeometry<ComposePathSink>? = null
    private var groupColors: List<Color> = emptyList()
    private var landingColors: List<Color> = emptyList()

    @Suppress("LongParameterList") // One frame's camera, playback and label inputs.
    fun draw(
        drawScope: DrawScope,
        pose: RangeCameraPose,
        progress: Float,
        labelHeightMeters: Float,
        minLabelPixels: Float,
        rollOutLabel: TextLayoutResult? = null,
        landedSeconds: Float = 0f,
    ) {
        if (!frame.prepare(pose, progress, landedSeconds)) return
        val projection = frame.projection ?: return
        with(drawScope) {
            drawBackdrop(projection)
            drawGround(projection)
            drawPolygons(scene.polygons)
            drawTrees()
            drawLabels(labelHeightMeters, minLabelPixels)
            val overlay = frame.overlay
            overlay?.let { drawOverlay(it) }
            if (frame.geometry == null) {
                if (overlay != null) drawRollOut(rollOutLabel)
                // Plan F8a2t: the kept earlier trails stay while no flight is shown.
                drawTrail()
            } else {
                if (progress >= 1f) {
                    drawPolygons(frame.landing)
                    drawRollOut(rollOutLabel)
                }
                drawFlight()
            }
        }
    }

    /** Strokes for the overlay, rebuilt only when the density changes (so a frame allocates nothing). */
    private var overlayStroke = Stroke()
    private var selectedStroke = Stroke()

    private fun DrawScope.drawOverlay(overlay: OverlayGeometry<ComposePathSink>) {
        if (overlay !== overlayColorsFor) {
            overlayColorsFor = overlay
            groupColors =
                overlay.groupColorIndices.map { OfClubPalette.color(it).copy(alpha = style.overlayTracerAlpha) }
            landingColors = overlay.landingColorIndices.map { OfClubPalette.color(it) }
        }
        val width = style.overlayStrokeWidth.dp.toPx()
        if (overlayStroke.width != width) {
            overlayStroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
            selectedStroke =
                Stroke(width = style.selectedStrokeWidth.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        }
        val stroke = overlayStroke
        for (index in overlay.groupPaths.indices) {
            drawPath(overlay.groupPaths[index].path, groupColors[index], style = stroke)
        }
        val dotRadius = style.overlayDotRadius.dp.toPx()
        for (index in overlay.flights.indices) {
            val x = overlay.landingXs[index]
            if (x.isNaN()) continue
            drawCircle(landingColors[index], radius = dotRadius, center = Offset(x, overlay.landingYs[index]))
        }
        val selected = overlay.selectedIndex
        if (selected >= 0) {
            drawPath(overlay.selectedPath.path, selectedColor, style = selectedStroke)
            val x = overlay.landingXs[selected]
            if (!x.isNaN()) {
                drawCircle(selectedColor, radius = dotRadius * 2, center = Offset(x, overlay.landingYs[selected]))
            }
        }
    }

    /** The carry → total segment, the total dot and its "est." label (plan F2/F8a1). */
    private fun DrawScope.drawRollOut(label: TextLayoutResult?) {
        if (!frame.rollOutVisible) return
        val start = Offset(frame.rollOutStartX, frame.rollOutStartY)
        val end = Offset(frame.rollOutEndX, frame.rollOutEndY)
        drawLine(rollOutColor, start, end, strokeWidth = style.rollOutStrokeWidth.dp.toPx(), pathEffect = rollOutDash)
        drawCircle(rollOutColor, radius = style.rollOutDotRadius.dp.toPx(), center = end)
        if (label != null) {
            drawText(
                label,
                topLeft =
                    Offset(
                        end.x - label.size.width / 2f,
                        end.y - label.size.height - style.rollOutDotRadius.dp.toPx() * 2,
                    ),
            )
        }
    }

    /**
     * Sky down to the horizon, then (plan F8a2a) the sun and the far ridges, then (plan F8a2p) the
     * flat rough from the horizon down; [drawGround] hazes it.
     */
    private fun DrawScope.drawBackdrop(projection: RangeProjection) {
        val horizon = scene.backdropHorizon(projection.height)
        if (horizon > 0f) {
            scale(scaleX = 1f, scaleY = horizon, pivot = Offset.Zero) {
                drawRect(skyGradient, size = Size(size.width, 1f))
            }
        }
        val sky = scene.sky
        if (sky.sunVisible) {
            translate(sky.sunX, sky.sunY) {
                scale(sky.sunGlowRadius, pivot = Offset.Zero) {
                    drawCircle(sunGlow, radius = 1f, center = Offset.Zero)
                }
            }
            drawCircle(sunDiscColor, radius = sky.sunDiscRadius, center = Offset(sky.sunX, sky.sunY))
        }
        val ridges = sky.ridges
        for (index in ridges.indices) {
            val ridge = ridges[index]
            if (ridge.visible) drawPath(ridge.path.path, ridgeColors[index])
        }
        if (horizon < projection.height) {
            drawRect(groundColor, topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
        }
    }

    /**
     * Plan F8a2p: the fairway, its soft stripes and the rest of the ground, then the haze overlay
     * from the horizon down, laid per pose from [RangeScene.hazeTopY] to [RangeScene.hazeBottomY].
     */
    private fun DrawScope.drawGround(projection: RangeProjection) {
        val fairway = scene.fairway
        if (fairway.visible) drawPath(fairway.path.path, fairwayColor)
        val stripes = scene.stripes
        for (index in stripes.indices) {
            val stripe = stripes[index]
            if (!stripe.gradientVisible) continue
            val gradient = stripeGradients[index]
            gradient.layOut(stripe.startX, stripe.startY, stripe.endX, stripe.endY)
            drawPath(stripe.polygon.path.path, gradient.brush)
        }
        drawPolygons(scene.groundPolygons)
        if (scene.hazeVisible) {
            val top = scene.backdropHorizon(projection.height)
            if (top < projection.height) {
                haze.layOut(0f, scene.hazeTopY, 0f, scene.hazeBottomY)
                drawRect(haze.brush, topLeft = Offset(0f, top), size = Size(size.width, projection.height - top))
            }
        }
    }

    /**
     * Plan F8a2t: every visible layer of the shot trail in order (the kept earlier trails, the
     * landing effect, then the style's outlines), in its packed colour or its club palette colour.
     */
    private fun DrawScope.drawTrail() {
        val layers = frame.trail.layers
        for (index in layers.indices) {
            val layer = layers[index]
            if (!layer.visible) continue
            val color =
                if (layer.paletteIndex >= 0) {
                    OfClubPalette.color(layer.paletteIndex).copy(alpha = (layer.argb ushr ALPHA_SHIFT) / CHANNEL_MAX)
                } else {
                    Color(layer.argb)
                }
            drawPath(layer.path.path, color)
        }
    }

    private fun DrawScope.drawPolygons(polygons: List<WorldPolygon<ComposePathSink>>) {
        for (index in polygons.indices) {
            val polygon = polygons[index]
            if (polygon.visible) drawPath(polygon.path.path, Color(polygon.argb))
        }
    }

    private fun DrawScope.drawTrees() {
        val order = scene.treeOrder
        for (position in order.indices) {
            val tree = scene.trees[order[position]]
            if (tree.trunk.visible) drawPath(tree.trunk.path.path, Color(tree.trunk.argb))
            val crowns = tree.crowns
            for (index in crowns.indices) {
                val crown = crowns[index]
                if (crown.visible) drawPath(crown.path.path, Color(crown.argb))
            }
        }
    }

    private fun DrawScope.drawLabels(
        labelHeightMeters: Float,
        minLabelPixels: Float,
    ) {
        val labels = scene.labels
        for (index in labels.indices) {
            val label = labels[index]
            if (!label.drawn || index >= labelLayouts.size) continue
            val layout = labelLayouts[index]
            val fontPixels = label.fontPixels(labelHeightMeters, minLabelPixels, labelFontPixels)
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

    /** The ball's shadow, the tracer up to the frame's position and the ball on its tip. */
    private fun DrawScope.drawFlight() {
        if (frame.shadowVisible) {
            val radiusX = frame.shadowRadiusX
            val radiusY = frame.shadowRadiusY
            drawOval(
                color = shadowColor,
                topLeft = Offset(frame.shadowX - radiusX, frame.shadowY - radiusY),
                size = Size(radiusX * 2, radiusY * 2),
            )
        }

        drawTrail()

        val tracer = frame.tracer
        val tipX = tracer.tipX
        val tipY = tracer.tipY
        if (!tipX.isNaN()) {
            drawCircle(color = ballColor, radius = frame.ballRadius, center = Offset(tipX, tipY))
        }
    }
}

private const val ALPHA_SHIFT = 24
private const val CHANNEL_MAX = 255f
