// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.insights.DispersionProjection
import dev.openflight.companion.core.insights.DispersionSample
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.distanceUnitLabel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// The club's landing spots and 68% ellipse, drawn with the shared DispersionProjection (plan F5).

@Composable
internal fun DispersionCard(dispersion: ClubDispersionState) {
    OfCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(BagTestTags.DISPERSION)
                .clearAndSetSemantics { contentDescription = dispersion.accessibilitySummary },
    ) {
        OfText(text = "Where it lands", role = OfTextRole.TitleSmall)
        val gold = OfColorTokens.Gold
        val grid = OfColorTokens.BgHover
        val dot = OfColorTokens.Cream
        Canvas(Modifier.fillMaxWidth().height(DISPERSION_HEIGHT)) {
            val projection = DispersionProjection(dispersion.viewport, size.width.toDouble(), size.height.toDouble())
            // Target line and distance arcs.
            drawLine(grid, Offset(projection.teeX.toFloat(), 0f), Offset(projection.teeX.toFloat(), size.height), 2f)
            dispersion.viewport.arcs.forEach { arc ->
                val (rx, ry) = projection.arcRadii(arc)
                drawOval(
                    color = grid,
                    topLeft = Offset((projection.teeX - rx).toFloat(), (projection.teeY - ry).toFloat()),
                    size = Size((2 * rx).toFloat(), (2 * ry).toFloat()),
                    style = Stroke(width = 1.5f),
                )
            }
            dispersion.ellipse?.let { ellipse ->
                val outline = projection.ellipseOutline(ellipse)
                val path =
                    Path().apply {
                        outline.forEachIndexed { index, (x, y) ->
                            if (index == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                        }
                        close()
                    }
                drawPath(path, gold.copy(alpha = 0.18f))
                drawPath(path, gold, style = Stroke(width = 2f))
            }
            dispersion.samples.forEach { sample ->
                drawCircle(
                    dot,
                    radius = 5f,
                    center =
                        Offset(
                            projection.x(sample.offlineYards).toFloat(),
                            projection.y(sample.carryYards).toFloat(),
                        ),
                )
            }
        }
    }
}

private val DISPERSION_HEIGHT = 220.dp
