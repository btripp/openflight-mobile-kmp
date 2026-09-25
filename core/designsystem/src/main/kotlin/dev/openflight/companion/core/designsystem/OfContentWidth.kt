// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The widest a form or a column of text gets on a tablet before it's centered. */
val OfContentMaxWidth: Dp = 840.dp

/**
 * Centers [content] horizontally and caps its width at [maxWidth] (default [OfContentMaxWidth]),
 * so forms and text stay readable on a tablet instead of stretching edge to edge. On a phone it's
 * a no-op: the content fills the width as before.
 */
@Composable
fun OfContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = OfContentMaxWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

@Preview(widthDp = 1280, heightDp = 400)
@Composable
private fun OfContentWidthPreview() {
    OfTheme {
        OfContentWidth(modifier = Modifier.fillMaxSize().background(OfColorTokens.BgDeep)) {
            OfCard(modifier = Modifier.fillMaxWidth().padding(OfSpacing.Xl)) {
                OfText(text = "Capped at 840 dp and centered", role = OfTextRole.Body)
            }
        }
    }
}
