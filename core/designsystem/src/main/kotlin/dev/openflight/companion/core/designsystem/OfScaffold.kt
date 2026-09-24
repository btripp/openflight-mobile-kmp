// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * The app-wide screen container: the dark background gradient plus a
 * [Scaffold] with a transparent container, so every screen (dashboard,
 * calibration, range) gets the same backdrop without repeating it.
 */
@Composable
fun OfScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(OfColorTokens.BgDeep, Color.Black),
                    ),
                ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = topBar,
            containerColor = Color.Transparent,
            contentColor = OfColorTokens.Cream,
        ) { padding ->
            content(padding)
        }
    }
}

@Preview
@Composable
private fun OfScaffoldPreview() {
    OfTheme {
        OfScaffold(topBar = { OfTopBar(title = "Launch Monitor", eyebrow = "OPENFLIGHT") }) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Content",
                    modifier = Modifier.padding(padding),
                    color = OfColorTokens.Cream,
                )
            }
        }
    }
}
