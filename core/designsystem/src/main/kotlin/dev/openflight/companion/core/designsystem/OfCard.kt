// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A translucent panel over the app background, matching the reference dashboard's
 * cards (the connection card, the shot card, the shot-history card). Content stacks
 * vertically with [OfSpacing.Sm] between children by default.
 *
 * @param elevated Uses the brighter "elevated" surface token, for a card nested
 * inside another card (for example the host field inside the connection card).
 */
@Composable
fun OfCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    contentSpacing: androidx.compose.ui.unit.Dp = OfSpacing.Sm,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = if (elevated) OfColorTokens.BgElevated else OfColorTokens.BgCard,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(OfSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
    }
}

@Preview
@Composable
private fun OfCardPreview() {
    OfTheme {
        OfCard {
            Text("Card content", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
