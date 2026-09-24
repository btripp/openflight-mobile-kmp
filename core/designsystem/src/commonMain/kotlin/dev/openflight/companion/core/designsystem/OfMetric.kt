// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A headline-sized metric tile, for example ball speed or carry on the reference
 * dashboard's shot card. Callers pass an already-formatted [value] (or "—" for a
 * missing reading), keeping this component free of any `core:model` dependency.
 */
@Composable
fun OfMetricPrimary(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(OfColorTokens.BgElevated, MaterialTheme.shapes.medium)
                .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = OfMetricValueFontSize,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = " $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = OfSpacing.Xs),
            )
        }
    }
}

/**
 * A small label/value metric for a detail grid, for example club speed or smash
 * factor. Renders no unit when [value] is "—", matching the reference's rule of
 * hiding units next to a null reading.
 */
@Composable
fun OfMetricDetail(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (unit.isNotEmpty() && value != "—") {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview
@Composable
private fun OfMetricPreview() {
    OfTheme {
        Column(
            modifier = Modifier.padding(OfSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
                OfMetricPrimary(title = "BALL SPEED", value = "142.3", unit = "MPH")
                OfMetricPrimary(title = "CARRY", value = "231", unit = "YDS")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Xxl)) {
                OfMetricDetail(title = "Club speed", value = "104.1", unit = "mph")
                OfMetricDetail(title = "Smash", value = "—")
            }
        }
    }
}
