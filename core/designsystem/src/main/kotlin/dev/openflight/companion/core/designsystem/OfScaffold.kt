// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * The app-wide screen container: the dark background gradient plus a
 * [Scaffold] with a transparent container, so every screen (dashboard,
 * calibration, range) gets the same backdrop without repeating it.
 *
 * @param bottomBar a screen's own bottom bar, if any. The app's top-level navigation lives in
 *   [OfAdaptiveScaffold] around the screens, not here.
 * @param messages where [OfMessageHostState.show] messages (snackbars) appear.
 */
@Composable
fun OfScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    messages: OfMessageHostState? = null,
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
            bottomBar = bottomBar,
            snackbarHost = {
                if (messages != null) {
                    SnackbarHost(messages.snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = OfColorTokens.BgHover,
                            contentColor = OfColorTokens.Cream,
                        )
                    }
                }
            },
            containerColor = Color.Transparent,
            contentColor = OfColorTokens.Cream,
        ) { padding ->
            content(padding)
        }
    }
}

/**
 * Transient messages for an [OfScaffold], for example a Pi error such as "Shot not found". Create
 * it with [rememberOfMessageHostState] and pass it to the scaffold's `messages`.
 */
@Stable
class OfMessageHostState internal constructor(
    internal val snackbarHostState: SnackbarHostState,
) {
    /** Shows [text] and suspends until it's dismissed or times out. */
    suspend fun show(text: String) {
        snackbarHostState.showSnackbar(text)
    }
}

@Composable
fun rememberOfMessageHostState(): OfMessageHostState = remember { OfMessageHostState(SnackbarHostState()) }

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
