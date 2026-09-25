// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfMetricDetail
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextButton
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.convertSpeedFromMph
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter

/**
 * The card for the selected shot: its number and club, then carry, spin and club speed. Delete
 * removes the shot straight from here (like swiping its row), so a bad reading spotted on the
 * chart doesn't have to be hunted down in the list. When editing is off ([deletable] false: over
 * Bluetooth, plan R8e) Delete stays visible but disabled; the actions row says why.
 */
@Composable
internal fun SelectedShotCardView(
    card: SelectedShotCard,
    units: UnitSystem,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deletable: Boolean = true,
) {
    OfCard(modifier = modifier.testTag(SessionTestTags.SELECTED), elevated = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfText(
                text = "Shot ${card.shotNumber} · ${card.clubName}",
                role = OfTextRole.TitleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OfTextButton(
                text = "Delete",
                onClick = onDelete,
                enabled = deletable,
                destructive = true,
                modifier = Modifier.testTag(SessionTestTags.SELECTED_DELETE),
            )
            OfTextButton(text = "Close", onClick = onClose, modifier = Modifier.testTag(SessionTestTags.SELECTED_CLOSE))
        }
        if (card.possibleBadRead) {
            OfText(
                text = DispersionCopy.BAD_READ_NOTE,
                role = OfTextRole.BodySmall,
                color = OfColorTokens.Warning,
                modifier = Modifier.testTag(SessionTestTags.BAD_READ_NOTE),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Lg)) {
            OfMetricDetail(
                title = "Carry",
                value = ShotMetricFormatter.number(card.carryYards?.let { convertDistanceFromYards(it, units) }, 0),
                unit = distanceUnitLabel(units),
                modifier = Modifier.weight(1f),
            )
            OfMetricDetail(
                title = "Spin",
                value = ShotMetricFormatter.number(card.spinRpm, 0),
                unit = "rpm",
                modifier = Modifier.weight(1f),
            )
            OfMetricDetail(
                title = "Club speed",
                value = ShotMetricFormatter.number(card.clubSpeedMph?.let { convertSpeedFromMph(it, units) }, 1),
                unit = speedUnitLabel(units),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val previewPoints =
    listOf(
        previewPoint(8, "driver", "D", 0, carry = 262.0, offline = -6.0),
        previewPoint(7, "driver", "D", 0, carry = 251.0, offline = 5.0),
        previewPoint(6, "driver", "D", 0, carry = 271.0, offline = 11.0),
        previewPoint(9, "7-iron", "7i", 1, carry = 214.0, offline = 18.0, badRead = true),
        previewPoint(5, "driver", "D", 0, carry = 258.0, offline = -1.0),
        previewPoint(4, "7-iron", "7i", 1, carry = 165.0, offline = -3.0),
        previewPoint(3, "7-iron", "7i", 1, carry = 158.0, offline = 4.0),
        previewPoint(2, "7-iron", "7i", 1, carry = 171.0, offline = 1.0),
        previewPoint(1, "7-iron", "7i", 1, carry = 162.0, offline = 0.0, estimated = true),
    )

@Suppress("LongParameterList")
private fun previewPoint(
    number: Int,
    club: String,
    label: String,
    colorIndex: Int,
    carry: Double,
    offline: Double,
    estimated: Boolean = false,
    badRead: Boolean = false,
) = DispersionPoint("p$number", number, club, label, colorIndex, carry, offline, estimated, badRead)

private fun previewDispersion(): SessionDispersionUiState {
    val ellipses =
        previewPoints.groupBy { it.club }.mapNotNull { (club, points) ->
            computeDispersionEllipse(
                points.filter { !it.sideEstimated && !it.possibleBadRead }.map { it.sample },
            )?.let { ClubDispersion(club, points.first().colorIndex, it) }
        }
    return SessionDispersionUiState(
        points = previewPoints,
        ellipses = ellipses,
        viewport = computeDispersionViewport(previewPoints.map { it.sample }, ellipses.map { it.ellipse })!!,
        estimatedSideCount = 1,
        possibleBadReadCount = 1,
    )
}

@Preview
@Composable
private fun DispersionCardPreview() {
    OfTheme {
        Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Lg)) {
            DispersionCard(previewDispersion(), UnitSystem.IMPERIAL, selectedId = "p6", onSelect = {})
            SelectedShotCardView(
                SelectedShotCard("p6", 6, "Driver", carryYards = 271.0, spinRpm = 2439.0, clubSpeedMph = 112.1),
                UnitSystem.IMPERIAL,
                onClose = {},
                onDelete = {},
            )
        }
    }
}
