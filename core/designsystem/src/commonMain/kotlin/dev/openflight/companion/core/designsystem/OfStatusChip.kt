// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The connection-status indicator: a colored dot plus a label, matching the
 * reference dashboard's status row. Feature code passes a plain [label] string (for
 * example `ConnectionState.description`) and the [tone] it maps to, so this
 * component has no dependency on `core:model`.
 */
@Composable
fun OfStatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val color = tone.color()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
        )
        Column(modifier = Modifier.padding(start = OfSpacing.Sm)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun StatusTone.color(): Color =
    when (this) {
        StatusTone.Positive -> OfColorTokens.Success
        StatusTone.InProgress -> OfColorTokens.Warning
        StatusTone.Negative -> OfColorTokens.Danger
        StatusTone.Neutral -> OfColorTokens.Neutral
    }

@Preview
@Composable
private fun OfStatusChipPreview() {
    OfTheme {
        Column {
            OfStatusChip(
                label = "OpenFlight Pi",
                tone = StatusTone.Positive,
                detail = "Connected",
                modifier = Modifier.padding(OfSpacing.Sm),
            )
            OfStatusChip(
                label = "Bluetooth",
                tone = StatusTone.InProgress,
                detail = "Scanning…",
                modifier = Modifier.padding(OfSpacing.Sm),
            )
            OfStatusChip(
                label = "Wi-Fi",
                tone = StatusTone.Negative,
                detail = "Too many devices",
                modifier = Modifier.padding(OfSpacing.Sm),
            )
        }
    }
}
