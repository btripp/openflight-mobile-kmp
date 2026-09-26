// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfContentWidth
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfPill
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSegmentedPicker
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.designsystem.StatusTone
import dev.openflight.companion.core.designsystem.rememberOfWindowClass
import dev.openflight.companion.core.insights.DispersionProjection
import dev.openflight.companion.core.insights.DispersionSample
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.model.GolfClub
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// Small pieces shared by the bag screens (plan F5).

/** The "est." badge on every estimated number (plan §0.2). */
@Composable
internal fun EstimatedBadge(modifier: Modifier = Modifier) {
    OfPill(
        label = BagCopy.ESTIMATED_BADGE,
        tone = StatusTone.Neutral,
        modifier = modifier.testTag(BagTestTags.ESTIMATED),
    )
}

/** A club's short label ("7i") in its colour: the club badge on every bag screen. */
@Composable
internal fun ClubBadge(
    shortLabel: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(
                BADGE_SIZE,
            ).height(BADGE_SIZE)
            .background(OfClubPalette.color(colorIndex), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        OfText(text = shortLabel, role = OfTextRole.Label, color = OfColorTokens.BgDeep)
    }
}

private val BADGE_SIZE = 40.dp
