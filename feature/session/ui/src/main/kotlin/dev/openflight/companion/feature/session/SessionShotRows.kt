// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwipeToDelete
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.insights.speedUnitLabel
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

private const val CLOCK_LENGTH = 8

/**
 * One `ShotList.tsx` row, deleted by an end-to-start swipe (or TalkBack's "Delete" action). Without
 * [deletable] (over Bluetooth, plan R8e) it is a plain row with no delete gesture. With [onSelect]
 * (the live session screen, not the stored-session detail) a tap selects it on the dispersion chart
 * either way; the [selected] row gets a gold outline.
 */
@Composable
internal fun SessionShotRowItem(
    shot: SessionShotRow,
    units: UnitSystem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deletable: Boolean = true,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
) {
    val tagged = modifier.testTag(SessionTestTags.shot(shot.id))
    if (deletable) {
        // The delete asks for confirmation first (plan R8f), so the row slides back meanwhile.
        OfSwipeToDelete(onDelete = onDelete, modifier = tagged, snapBack = true) {
            ShotRowContent(shot, units, selected, onSelect)
        }
    } else {
        Row(modifier = tagged) { ShotRowContent(shot, units, selected, onSelect) }
    }
}

@Composable
private fun ShotRowContent(
    shot: SessionShotRow,
    units: UnitSystem,
    selected: Boolean,
    onSelect: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(OfColorTokens.BgCard, shape)
                .border(1.dp, if (selected) OfColorTokens.Gold else Color.Transparent, shape)
                .then(
                    if (onSelect != null) {
                        Modifier
                            .clickable(onClickLabel = "Show on chart", onClick = onSelect)
                            .semantics { this.selected = selected }
                    } else {
                        // One TalkBack stop per row, like the selectable rows.
                        Modifier.semantics(mergeDescendants = true) {}
                    },
                ).padding(horizontal = OfSpacing.Lg, vertical = OfSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
    ) {
        OfText(text = "#${shot.shotNumber}", role = OfTextRole.Label, color = OfColorTokens.Gold)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Two lines, so large text wraps instead of cutting the club or the time off.
            OfText(text = shot.implementLabel ?: clubLabel(shot.club), role = OfTextRole.TitleSmall, maxLines = 2)
            OfText(
                text = listOfNotNull(clockTime(shot.timestamp), shot.profileName).joinToString(" · "),
                role = OfTextRole.BodySmall,
                color = OfColorTokens.CreamDim,
                maxLines = 2,
            )
        }
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
}

@Composable
private fun RowMetric(
    value: String,
    unit: String,
) {
    Column(modifier = Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.End) {
        OfText(text = value, role = OfTextRole.TitleSmall, maxLines = 1)
        OfText(text = unit, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
    }
}

/** `"2026-07-29T19:42:10.123456"` → `"19:42:10"`; `null` when it isn't an ISO timestamp. */
internal fun clockTime(timestamp: String): String? =
    timestamp.substringAfter('T', missingDelimiterValue = "").take(CLOCK_LENGTH).ifEmpty { null }

/** A wire club value's display name ("7-iron" → "7-Iron"), or the raw value for an unknown club. */
internal fun clubLabel(wire: String): String = GolfClub.fromWireValue(wire)?.displayName ?: wire.ifEmpty { "Unknown" }

internal val previewRows =
    listOf(
        previewRow("B0D91F0A-7950-4D7E-9DD5-AF9777C190E2", 2, "7-iron", 118.2, 165.0),
        previewRow("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1", 1, "driver", 151.4, 264.0),
    )

internal fun previewRow(
    id: String,
    number: Int,
    club: String,
    ballSpeed: Double,
    carry: Double,
): SessionShotRow =
    SessionShotRow(
        id = id,
        shotNumber = number,
        timestamp = "2026-07-29T19:42:1$number.000000",
        club = club,
        profileName = null,
        ballSpeedMph = ballSpeed,
        clubSpeedMph = null,
        launchAngleVerticalDeg = 12.0,
        spinRpm = 2400.0,
        carryYards = carry,
        isSwingSpeed = false,
        swingSpeedMph = null,
        readingCount = null,
        triggerSpeedMph = null,
        durationMs = null,
        implementLabel = null,
        enrichment = null,
    )

@Preview
@Composable
private fun SessionScreenPreview() {
    OfTheme {
        SessionScreen(
            uiState =
                SessionUiState(
                    source = SessionSource.PI,
                    allCount = 2,
                    clubChips = listOf(ClubChip("driver", 1), ClubChip("7-iron", 1)),
                    shots = previewRows,
                    showSimulateShot = true,
                    simulateAvailability = PiFeatureAvailability.Available,
                ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun SessionEmptyPreview() {
    OfTheme { SessionScreen(uiState = SessionUiState(), onEvent = {}, onBack = {}) }
}
