// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview

/** One entry of an [OfOverflowMenu]. */
data class OfMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val testTag: String? = null,
)

/**
 * A top bar's overflow ("more actions") menu: a three-dot button that opens [items] (plan F1d, for
 * example Practice's "Speed training"). Put it in [OfTopBar]'s `actions`.
 *
 * @param contentDescription what the button says to TalkBack.
 */
@Composable
fun OfOverflowMenu(
    items: List<OfMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = "More options",
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            OfIcon(imageVector = OfIcons.More, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    modifier = item.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }
    }
}

@Preview
@Composable
private fun OfOverflowMenuPreview() {
    OfTheme {
        OfTopBar(
            title = "Launch Monitor",
            eyebrow = "OPENFLIGHT",
            actions = { OfOverflowMenu(items = listOf(OfMenuItem("Speed training", onClick = {}))) },
        )
    }
}
