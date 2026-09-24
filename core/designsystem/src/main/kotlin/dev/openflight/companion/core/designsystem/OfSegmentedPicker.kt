// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * A two-or-more-way segmented picker, for example the Bluetooth/Wi-Fi transport
 * picker on the dashboard. [options] and [selected] are plain strings so this
 * component has no dependency on `core:model`'s enums.
 */
@Composable
fun OfSegmentedPicker(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = OfColorTokens.BgElevated,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            ) {
                Text(option)
            }
        }
    }
}

@Preview
@Composable
private fun OfSegmentedPickerPreview() {
    OfTheme {
        OfSegmentedPicker(
            options = listOf("Bluetooth", "Wi-Fi"),
            selected = "Bluetooth",
            onSelect = {},
            modifier = Modifier.padding(OfSpacing.Md),
        )
    }
}
