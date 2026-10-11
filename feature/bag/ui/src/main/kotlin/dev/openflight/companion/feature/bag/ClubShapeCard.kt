// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.flight.FlightPathProjector
import dev.openflight.companion.core.flight.ScreenPoint

/**
 * The club's shot shape at a glance: its typical shape ("Mostly fade"), how many shots went each
 * way, and its recent flights drawn over each other from above (the newest brightest), so a
 * pattern shows without opening any one shot.
 */
@Composable
internal fun ShapePatternCard(
    pattern: ShapePatternState,
    modifier: Modifier = Modifier,
) {
    OfCard(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(BagTestTags.SHAPE_PATTERN)
                .clearAndSetSemantics { contentDescription = pattern.accessibilitySummary },
    ) {
        OfText(text = "Shot shape", role = OfTextRole.TitleSmall)
        OfText(text = pattern.headline, role = OfTextRole.Title, color = OfColorTokens.Gold)
        OfText(text = pattern.talliesLabel, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
        if (pattern.flights.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(PATTERN_HEIGHT)) {
                val projected =
                    FlightPathProjector.projectPattern(pattern.flights, size.width, size.height, 12.dp.toPx())
                        ?: return@Canvas
                val thin = 1.dp.toPx()
                projected.guides.forEach {
                    drawLine(
                        OfColorTokens.BgHover,
                        it.first().offset(),
                        it.last().offset(),
                        thin,
                    )
                }
                drawLine(
                    OfColorTokens.CreamMuted,
                    projected.targetLine.first().offset(),
                    projected.targetLine.last().offset(),
                    thin,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )
                // Oldest first, so the newest flight is drawn on top.
                projected.flights.indices.reversed().forEach { index ->
                    val newest = index == 0
                    val color = if (newest) OfColorTokens.Gold else OfColorTokens.GoldDim.copy(alpha = OLDER_ALPHA)
                    val points = projected.flights[index]
                    val path =
                        Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                    drawPath(
                        path,
                        color,
                        style = Stroke(width = (if (newest) 3 else 2).dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawCircle(color, radius = 3.dp.toPx(), center = points.last().offset())
                }
            }
            OfText(
                text = "Last ${pattern.flights.size} from above, newest brightest",
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamMuted,
            )
        }
    }
}

private fun ScreenPoint.offset(): Offset = Offset(x, y)

private val PATTERN_HEIGHT = 200.dp
private const val OLDER_ALPHA = 0.55f
