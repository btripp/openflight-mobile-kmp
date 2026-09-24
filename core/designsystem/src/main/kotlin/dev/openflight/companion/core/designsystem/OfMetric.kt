// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A headline-sized metric tile, for example ball speed or carry on the reference
 * dashboard's shot card. Callers pass an already-formatted [value] (or "—" for a
 * missing reading), keeping this component free of any `core:model` dependency.
 *
 * The title, value and unit are three separate `Text` nodes so they can be styled
 * independently, but [Modifier.semantics] with `mergeDescendants = true` collapses them
 * into one TalkBack/VoiceOver stop reading a full phrase, for example
 * "Ball speed, 139.1 miles per hour", instead of three separate announcements.
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
                .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Md)
                .semantics(mergeDescendants = true) {
                    contentDescription = metricContentDescription(title, value, unit)
                },
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        // FlowRow (not Row): at a large system font scale, "142.3" plus " MPH" can be wider
        // than the tile. Row would force the unit to wrap character-by-character ("MP"/"H");
        // FlowRow wraps the whole unit token onto its own line instead.
        FlowRow(itemVerticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = OfMetricValueFontSize,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = unitSuffix(unit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = OfSpacing.Xs),
            )
        }
    }
}

/**
 * A small label/value metric for a detail grid, for example club speed or smash
 * factor. Renders no unit when [value] is "—", matching the reference's rule of
 * hiding units next to a null reading. See [OfMetricPrimary] for why the whole tile
 * carries one merged [contentDescription].
 */
@Composable
fun OfMetricDetail(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = metricContentDescription(title, value, unit)
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // See OfMetricPrimary above for why this is a FlowRow rather than a Row.
        FlowRow(itemVerticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (unit.isNotEmpty() && value != "—") {
                Text(
                    text = unitSuffix(unit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A word-like unit (`mph`, `rpm`, `yds`) reads as a separate token and keeps a leading space.
 * A degree sign attaches directly to the number ("9.5°", never "9.5 °"), matching standard
 * typographic convention and the reference iOS app's rendering.
 */
internal fun unitSuffix(unit: String): String = if (unit == "°") unit else " $unit"

/** The placeholder [OfMetricPrimary]/[OfMetricDetail] show for a measurement with no reading. */
private const val MISSING_METRIC_VALUE = "—"

/**
 * Builds the one merged phrase TalkBack/VoiceOver reads for a metric tile, for example
 * "Ball speed, 139.1 miles per hour" or "Smash, no reading". [title] arrives in whatever case
 * the caller's copy uses (`"BALL SPEED"` for a primary metric, `"Club speed"` for a detail
 * one); normalizing to sentence case keeps the announcement natural either way. Abbreviated
 * units (`mph`, `yds`, `rpm`, `°`) are spelled out because a screen reader sounds out an
 * abbreviation letter by letter otherwise. Public so feature modules can build the same kind
 * of merged phrase for rows that aren't built from [OfMetricPrimary]/[OfMetricDetail], for
 * example a shot-history row.
 */
fun metricContentDescription(
    title: String,
    value: String,
    unit: String = "",
): String {
    val spokenTitle = title.lowercase().replaceFirstChar { it.uppercase() }
    if (value == MISSING_METRIC_VALUE) return "$spokenTitle, no reading"
    val spokenUnit = spokenUnit(unit)
    return if (spokenUnit.isEmpty()) "$spokenTitle, $value" else "$spokenTitle, $value $spokenUnit"
}

private fun spokenUnit(unit: String): String =
    when (unit.trim().lowercase()) {
        "" -> ""
        "mph" -> "miles per hour"
        "yds" -> "yards"
        "rpm" -> "revolutions per minute"
        "°" -> "degrees"
        else -> unit
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
