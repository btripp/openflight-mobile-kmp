// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private val ChipShape = RoundedCornerShape(999.dp)
private const val DISABLED_ALPHA = 0.45f

/**
 * A pill with an optional count, for example a club tab "Driver 3" (`StatsView.tsx`) or an
 * implement in the training picker. [onClick] `null` makes it display-only (the dashboard's club
 * chips); [selected] fills it gold.
 *
 * @param minTouchTarget pads a tappable chip's touch area out to 48 dp (the pill keeps its size),
 *   for screens that have been through the plan R8f accessibility pass.
 */
@Composable
fun OfChip(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    minTouchTarget: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val container = if (selected) OfColorTokens.Gold else OfColorTokens.BgElevated
    val content = if (selected) OfColorTokens.BgDeep else OfColorTokens.Cream
    val border = if (selected) OfColorTokens.Gold else Color.White.copy(alpha = 0.08f)
    Row(
        modifier =
            modifier
                .then(
                    if (onClick != null) {
                        Modifier
                            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
                            .then(if (minTouchTarget) Modifier.minimumInteractiveComponentSize() else Modifier)
                    } else {
                        Modifier
                    },
                ).alpha(if (enabled) 1f else DISABLED_ALPHA)
                .background(container, ChipShape)
                .border(BorderStroke(1.dp, border), ChipShape)
                .semantics { this.selected = selected }
                .padding(horizontal = OfSpacing.Md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) OfColorTokens.BgDeep else OfColorTokens.Gold,
            )
        }
    }
}

/**
 * The web UI's confidence badge (`ShotDisplay.tsx`'s `MetricCard`): up to [maxDots] dots,
 * [filledDots] of them gold, then [label] ("high", "medium", "low"). [showDots] `false` shows the
 * label only ("experimental").
 */
@Composable
fun OfConfidenceDots(
    filledDots: Int,
    label: String,
    modifier: Modifier = Modifier,
    maxDots: Int = 3,
    showDots: Boolean = true,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label confidence" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (showDots) {
            repeat(maxDots) { index ->
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .background(
                                if (index <
                                    filledDots
                                ) {
                                    OfColorTokens.Gold
                                } else {
                                    OfColorTokens.CreamMuted.copy(alpha = 0.3f)
                                },
                                CircleShape,
                            ),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OfColorTokens.CreamDim,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * A status pill with a colored dot, for example a simulator connector (`SimStatus.tsx`) or the
 * session's source badge. [detail] is a second, dimmer line.
 */
@Composable
fun OfPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val color =
        when (tone) {
            StatusTone.Positive -> OfColorTokens.Success
            StatusTone.InProgress -> OfColorTokens.Warning
            StatusTone.Negative -> OfColorTokens.Danger
            StatusTone.Neutral -> OfColorTokens.Neutral
        }
    Row(
        modifier =
            modifier
                .background(color.copy(alpha = 0.12f), ChipShape)
                .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), ChipShape)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = OfSpacing.Md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Column {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = OfColorTokens.Cream)
            detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = OfColorTokens.CreamDim) }
        }
    }
}

@Preview
@Composable
private fun OfChipsPreview() {
    OfTheme {
        Column(modifier = Modifier.padding(OfSpacing.Md), verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                OfChip(label = "All", count = 5, selected = true, onClick = {})
                OfChip(label = "Driver", count = 3, onClick = {})
                OfChip(label = "7-Iron", count = 2)
            }
            OfConfidenceDots(filledDots = 2, label = "medium")
            OfPill(label = "GSPro", tone = StatusTone.Positive, detail = "192.168.1.20:921")
        }
    }
}
