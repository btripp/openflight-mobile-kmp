// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * A labelled on/off switch row, for example the camera's "Ball detection" or the debug toggle.
 * When [enabled] is false, [disabledReason] shows under the label (plan R6b: every Wi-Fi-only
 * control explains why it's disabled instead of disappearing).
 */
@Composable
fun OfSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: String? = null,
    disabledReason: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = OfSpacing.Md)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = OfColorTokens.Cream)
            detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = OfColorTokens.CreamDim) }
            if (!enabled && disabledReason != null) OfDisabledReason(disabledReason)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = OfColorTokens.BgDeep,
                    checkedTrackColor = OfColorTokens.Gold,
                    uncheckedThumbColor = OfColorTokens.CreamDim,
                    uncheckedTrackColor = OfColorTokens.BgElevated,
                ),
        )
    }
}

/**
 * An integer slider with its label and live value, for example the radar panel's "Min Speed"
 * (`DebugPanel.tsx`'s `SliderControl`). The thumb moves locally while dragging; [onValueCommitted]
 * fires once on release, like the web UI's `onMouseUp`, so the Pi gets one update per drag.
 *
 * @param unit appended to the value as-is, for example " mph".
 */
@Composable
fun OfSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onValueCommitted: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = snap(dragging, range, step)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = OfColorTokens.CreamDim,
                modifier = Modifier.weight(1f),
            )
            Text(text = "$shown$unit", style = MaterialTheme.typography.titleMedium, color = OfColorTokens.Cream)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onValueCommitted(snap(dragging, range, step)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.semantics { stateDescription = "$shown$unit" },
            colors =
                SliderDefaults.colors(
                    thumbColor = OfColorTokens.Gold,
                    activeTrackColor = OfColorTokens.Gold,
                    inactiveTrackColor = OfColorTokens.BgHover,
                ),
        )
        if (!enabled && disabledReason != null) OfDisabledReason(disabledReason)
    }
}

private fun snap(
    value: Float,
    range: IntRange,
    step: Int,
): Int {
    val steps = ((value - range.first) / step).roundToInt()
    return (range.first + steps * step).coerceIn(range)
}

/** The small "why is this disabled" line under a control, for example "Requires Wi-Fi". */
@Composable
fun OfDisabledReason(
    reason: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = reason,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = OfColorTokens.Warning,
    )
}

@Preview
@Composable
private fun OfControlsPreview() {
    OfTheme {
        OfCard(modifier = Modifier.padding(OfSpacing.Md)) {
            Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
                OfSwitchRow(label = "Debug mode", checked = true, onCheckedChange = {}, detail = "/tmp/debug.jsonl")
                OfSwitchRow(
                    label = "Ball detection",
                    checked = false,
                    onCheckedChange = {},
                    enabled = false,
                    disabledReason = "Requires Wi-Fi",
                )
                OfSlider(label = "Min Speed", value = 12, range = 0..50, step = 1, onValueCommitted = {}, unit = " mph")
            }
        }
    }
}
