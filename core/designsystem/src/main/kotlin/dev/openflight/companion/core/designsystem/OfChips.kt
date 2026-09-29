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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val ChipShape = RoundedCornerShape(999.dp)

/**
 * Issue #65: once an [OfPill]'s text wraps, fully rounded ends would curve into its first and last
 * lines, so a wrapped pill becomes a rounded rectangle. Its 12 dp corners sit inside the 6 dp
 * vertical padding plus half a line, so every line keeps the full [OfSpacing.Md] side padding.
 */
private val WrappedPillShape = RoundedCornerShape(OfSpacing.Md)
private const val DISABLED_ALPHA = 0.45f

/**
 * A pill with an optional count, for example a club tab "Driver 3" (`StatsView.tsx`) or an
 * implement in the training picker. [onClick] `null` makes it display-only (the dashboard's club
 * chips); [selected] fills it gold.
 *
 * @param minTouchTarget pads a tappable chip's touch area out to 48 dp (the pill keeps its size).
 *   Defaults to true (plan F1b): every tappable chip gets a minimum touch target unless a caller
 *   opts out for a reason. Display-only chips ([onClick] `null`) ignore it either way.
 */
@Composable
fun OfChip(
    label: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    minTouchTarget: Boolean = true,
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
 * [OfPill]'s outline: fully rounded while its text is one line, a rounded rectangle once it wraps.
 * The flags are set from the texts' layouts and only read while drawing (background and border
 * observe the read), so the right shape shows on the first frame, without another composition.
 */
@Stable
private class PillShape : Shape {
    var labelWrapped by mutableStateOf(false)
    var detailWrapped by mutableStateOf(false)
    private val wrapped: Boolean get() = labelWrapped || detailWrapped

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = (if (wrapped) WrappedPillShape else ChipShape).createOutline(size, layoutDirection, density)
}

/**
 * A status pill with a colored dot, for example a simulator connector (`SimStatus.tsx`) or the
 * session's source badge. [detail] is a second, dimmer line. A one-line pill has fully rounded
 * ends; if [label] or [detail] wraps (a long reconnect reason, large text), the pill becomes a
 * rounded rectangle so the text doesn't crowd its curved edges (issue #65). The dot stays either
 * way, so the status never relies on colour alone.
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
    val shape = remember { PillShape() }
    if (detail == null) SideEffect { shape.detailWrapped = false }
    Row(
        modifier =
            modifier
                .background(color.copy(alpha = 0.12f), shape)
                .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), shape)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = OfSpacing.Md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = OfColorTokens.Cream,
                onTextLayout = { shape.labelWrapped = it.lineCount > 1 },
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = OfColorTokens.CreamDim,
                    onTextLayout = { layout -> shape.detailWrapped = layout.lineCount > 1 },
                )
            }
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

/** Issue #65: a long status wraps at 200% text; the pill turns into a rounded rectangle. */
@Preview(widthDp = 380, fontScale = 2f)
@Composable
private fun OfPillWrappedPreview() {
    OfTheme {
        Column(modifier = Modifier.padding(OfSpacing.Md), verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
            OfPill(label = "Connected", tone = StatusTone.Positive)
            OfPill(
                label =
                    "Reconnecting: Unable to resolve host \"raspberrypi.local\": " +
                        "No address associated with hostname",
                tone = StatusTone.InProgress,
            )
        }
    }
}
