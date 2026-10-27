// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

// The pieces of a shot row, laid out in one row or, at a large font scale, two (SessionShotRows.kt).

@Composable
internal fun ShotNumber(shot: SessionShotRow) {
    OfText(text = "#${shot.shotNumber}", role = OfTextRole.Label, color = OfColorTokens.Gold)
}

@Composable
internal fun ShotDetails(
    shot: SessionShotRow,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OfText(text = shot.implementLabel ?: clubLabel(shot.club), role = OfTextRole.TitleSmall, maxLines = maxLines)
        OfText(
            text = listOfNotNull(clockTime(shot.timestamp), shot.profileName, shot.shapeLabel).joinToString(" · "),
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
            maxLines = maxLines,
        )
        // Plan F5b: the estimated total and roll, labelled "est.".
        shot.estimatedTotal?.let {
            OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim, maxLines = maxLines)
        }
    }
}

@Composable
internal fun ShotMetrics(
    shot: SessionShotRow,
    units: UnitSystem,
) {
    if (shot.isSwingSpeed) {
        RowMetric(shot.swingSpeedMph?.let { speedValue(it, units) } ?: ShotMetricFormatter.MISSING, "SWING")
    } else {
        RowMetric(
            shot.ballSpeedMph?.let {
                speedValue(it, units)
            } ?: ShotMetricFormatter.MISSING,
            speedUnitLabel(units).uppercase(),
        )
        RowMetric(
            ShotMetricFormatter.number(shot.carryYards?.let { convertDistanceFromYards(it, units) }, 0),
            distanceUnitLabel(units).uppercase(),
        )
    }
}

@Composable
internal fun ViewOnRange(
    shot: SessionShotRow,
    onViewOnRange: (() -> Unit)?,
) {
    // Plan F8d: only a shot with the ball speed and carry a flight needs can be viewed.
    if (onViewOnRange != null && shot.canFly) {
        OfTextButton(
            text = "Range",
            onClick = onViewOnRange,
            modifier = Modifier.testTag(SessionTestTags.viewOnRange(shot.id)),
        )
    }
}

@Composable
internal fun RowMetric(
    value: String,
    unit: String,
) {
    Column(modifier = Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.End) {
        OfText(text = value, role = OfTextRole.TitleSmall, maxLines = 1)
        OfText(text = unit, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
    }
}
