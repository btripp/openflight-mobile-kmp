// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A tap-to-open menu that shows [selected] and offers [options], for example the
 * "Club for next shot" menu on the dashboard and the driving-range overlay. Options
 * and the selection are plain strings, matching `OfStatusChip`'s primitives-only
 * contract; a feature maps its own enum to/from a display string.
 *
 * @param isBusy Shows a spinner in place of the chevron, for example while a
 * `setClub` request is in flight, and disables the menu along with [enabled].
 */
@Composable
fun OfDropdownMenu(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBusy: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactive = enabled && !isBusy

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(OfColorTokens.BgElevated, RoundedCornerShape(12.dp))
                .clickable(enabled = interactive) { expanded = true }
                .padding(horizontal = OfSpacing.Md, vertical = OfSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = OfSpacing.Sm),
        )
        Text(
            text = selected,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
        DropdownMenu(expanded = expanded && interactive, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    leadingIcon =
                        if (option == selected) {
                            { CheckmarkIndicator() }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun CheckmarkIndicator() {
    Icon(
        imageVector = OfCheckmarkIcon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A minimal checkmark vector so dropdown items don't pull in Material Icons
 * Extended just for one glyph.
 */
private val OfCheckmarkIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "OfCheck",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 16.17f)
                lineTo(4.83f, 12f)
                lineTo(3.41f, 13.41f)
                lineTo(9f, 19f)
                lineTo(21f, 7f)
                lineTo(19.59f, 5.59f)
                close()
            }
        }.build()
}

@Preview
@Composable
private fun OfDropdownMenuPreview() {
    OfTheme {
        OfDropdownMenu(
            label = "NEXT CLUB",
            selected = "7-Iron",
            options = listOf("Driver", "7-Iron", "Pitching Wedge"),
            onSelect = {},
            modifier = Modifier.padding(OfSpacing.Md),
        )
    }
}
