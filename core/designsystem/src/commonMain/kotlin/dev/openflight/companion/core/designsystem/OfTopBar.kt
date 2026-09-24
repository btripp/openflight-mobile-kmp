// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * The dashboard-style header: a small eyebrow label above a large title, matching
 * the reference's "OPENFLIGHT" / "Launch Monitor" header, plus optional trailing
 * [actions] such as the Range entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfTopBar(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                if (eyebrow != null) {
                    Text(
                        text = eyebrow,
                        style = OfEyebrowTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
    )
}

@Preview
@Composable
private fun OfTopBarPreview() {
    OfTheme {
        OfTopBar(title = "Launch Monitor", eyebrow = "OPENFLIGHT")
    }
}
