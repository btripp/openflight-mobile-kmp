// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.DispersionProjection
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

private val ChartHeight = 280.dp
private val DotRadius = 11.dp
private val SelectedRingGap = 3.dp
private val TapRadius = 24.dp
private val ArcLabelInset = 4.dp
private val ArcStroke = 1.dp
private val EllipseStroke = 1.5.dp
private val HollowDotStroke = 2.dp
private val SelectedRingStroke = 2.5.dp
private val GridColor = OfColorTokens.CreamMuted.copy(alpha = 0.35f)
private val CentreLineColor = OfColorTokens.CreamMuted.copy(alpha = 0.25f)
private val SelectedRingColor = Color.White
private const val ELLIPSE_FILL_ALPHA = 0.08f
private const val DASH_ON = 6f
private const val DASH_OFF = 6f
private const val ARC_LABEL_SP = 11
private const val DOT_LABEL_SP = 9

/**
 * The top-down dispersion chart: distance arcs around the tee, one dot per shot (club colour and
 * short label; hollow when its side was estimated), an ellipse per club and a ring on the
 * selected shot. Tapping a dot selects it; tapping elsewhere clears the selection.
 */
@Composable
internal fun DispersionCard(
    dispersion: SessionDispersionUiState,
    units: UnitSystem,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val currentOnSelect by rememberUpdatedState(onSelect)
    val unitSuffix = if (units == UnitSystem.METRIC) "m" else "y"
    OfCard(modifier = modifier.testTag(SessionTestTags.DISPERSION)) {
        OfText(text = "DISPERSION", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ChartHeight)
                    .clipToBounds()
                    .semantics { contentDescription = chartDescription(dispersion) }
                    .pointerInput(dispersion) {
                        detectTapGestures { tap ->
                            val projection =
                                DispersionProjection(dispersion.viewport, size.width.toDouble(), size.height.toDouble())
                            val index =
                                projection.nearest(
                                    samples = dispersion.points.map { it.sample },
                                    tapX = tap.x.toDouble(),
                                    tapY = tap.y.toDouble(),
                                    radius = TapRadius.toPx().toDouble(),
                                )
                            currentOnSelect(index?.let { dispersion.points[it].id })
                        }
                    }.drawWithCache {
                        val projection =
                            DispersionProjection(dispersion.viewport, size.width.toDouble(), size.height.toDouble())
                        val labels = ChartLabels(textMeasurer)
                        onDrawBehind {
                            drawArcs(dispersion, projection, labels, unitSuffix)
                            drawCentreLine(projection)
                            drawEllipses(dispersion, projection)
                            drawDots(dispersion, projection, labels, selectedId)
                        }
                    },
        )
        dispersion.clubSpread?.let { spread ->
            OfText(
                text = DispersionCopy.spreadSummary(spread, units),
                role = OfTextRole.BodySmall,
                modifier = Modifier.testTag(SessionTestTags.SPREAD),
            )
        }
        if (dispersion.possibleBadReadCount > 0) {
            OfText(
                text = DispersionCopy.badReadCaption(dispersion.possibleBadReadCount),
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Warning,
            )
        }
        if (dispersion.estimatedSideCount > 0) {
            OfText(
                text = estimatedCaption(dispersion.estimatedSideCount),
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
            )
        }
    }
}

internal fun estimatedCaption(count: Int): String =
    if (count == 1) {
        "1 shot has no side data and sits on the centre line (hollow dot)."
    } else {
        "$count shots have no side data and sit on the centre line (hollow dots)."
    }

private fun chartDescription(dispersion: SessionDispersionUiState): String {
    val clubs =
        dispersion.points
            .map { clubLabel(it.club) }
            .distinct()
            .joinToString(", ")
    val shots = if (dispersion.points.size == 1) "1 shot" else "${dispersion.points.size} shots"
    return "Dispersion chart, $shots: $clubs. Select a shot in the list to see its details."
}

/** Text layouts for arc labels and dot labels, measured once per draw cache. */
private class ChartLabels(
    private val measurer: TextMeasurer,
) {
    private val cache = HashMap<Pair<String, Color>, TextLayoutResult>()

    fun layout(
        text: String,
        color: Color,
        sizeSp: Int,
    ): TextLayoutResult =
        cache.getOrPut(text to color) {
            measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold))
        }
}

/**
 * Distance arcs around the tee (usually below the chart; ovals when offline is stretched),
 * labelled at the right edge where the label fits inside the chart.
 */
private fun DrawScope.drawArcs(
    dispersion: SessionDispersionUiState,
    projection: DispersionProjection,
    labels: ChartLabels,
    unitSuffix: String,
) {
    for (arc in dispersion.viewport.arcs) {
        val (radiusX, radiusY) = projection.arcRadii(arc)
        drawOval(
            GridColor,
            topLeft = Offset((projection.teeX - radiusX).toFloat(), (projection.teeY - radiusY).toFloat()),
            size = Size((radiusX * 2).toFloat(), (radiusY * 2).toFloat()),
            style = Stroke(ArcStroke.toPx()),
        )
        val layout = labels.layout("${arc.label}$unitSuffix", OfColorTokens.CreamDim, ARC_LABEL_SP)
        val labelX = size.width - layout.size.width - ArcLabelInset.toPx()
        val arcY = projection.arcY(arc, atX = (labelX + layout.size.width / 2f).toDouble()) ?: continue
        val labelTop = arcY.toFloat() - layout.size.height - ArcLabelInset.toPx() / 2
        if (labelTop >= 0f && labelTop + layout.size.height <= size.height) {
            drawText(layout, topLeft = Offset(labelX, labelTop))
        }
    }
}

private fun DrawScope.drawCentreLine(projection: DispersionProjection) {
    val x = projection.teeX.toFloat()
    drawLine(
        color = CentreLineColor,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = ArcStroke.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)),
    )
}

private fun DrawScope.drawEllipses(
    dispersion: SessionDispersionUiState,
    projection: DispersionProjection,
) {
    for (club in dispersion.ellipses) {
        val color = OfClubPalette.color(club.colorIndex)
        val outline = projection.ellipseOutline(club.ellipse)
        val path =
            Path().apply {
                outline.forEachIndexed { index, (x, y) ->
                    if (index == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                }
                close()
            }
        drawPath(path, color.copy(alpha = ELLIPSE_FILL_ALPHA))
        drawPath(path, color, style = Stroke(EllipseStroke.toPx()))
    }
}

/** Oldest first, so the newest shot sits on top; the selected shot is drawn last of all. */
private fun DrawScope.drawDots(
    dispersion: SessionDispersionUiState,
    projection: DispersionProjection,
    labels: ChartLabels,
    selectedId: String?,
) {
    val radius = DotRadius.toPx()
    val ordered = dispersion.points.asReversed().sortedBy { it.id == selectedId }
    for (point in ordered) {
        val color = OfClubPalette.color(point.colorIndex)
        val center = Offset(projection.x(point.offlineYards).toFloat(), projection.y(point.carryYards).toFloat())
        val labelColor: Color
        if (point.sideEstimated) {
            drawCircle(OfColorTokens.BgDeep, radius = radius, center = center)
            drawCircle(
                color,
                radius = radius - HollowDotStroke.toPx() / 2,
                center = center,
                style = Stroke(HollowDotStroke.toPx()),
            )
            labelColor = color
        } else {
            drawCircle(color, radius = radius, center = center)
            labelColor = OfColorTokens.BgDeep
        }
        val layout = labels.layout(point.shortLabel, labelColor, DOT_LABEL_SP)
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
        var ringRadius = radius + SelectedRingGap.toPx()
        if (point.possibleBadRead) {
            drawCircle(
                OfColorTokens.Warning,
                radius = ringRadius,
                center = center,
                style = Stroke(SelectedRingStroke.toPx()),
            )
            ringRadius += SelectedRingGap.toPx() + SelectedRingStroke.toPx()
        }
        if (point.id == selectedId) {
            drawCircle(
                SelectedRingColor,
                radius = ringRadius,
                center = center,
                style = Stroke(SelectedRingStroke.toPx()),
            )
        }
    }
}
