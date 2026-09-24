// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Filled primary action button, for example "Retry" or "Calibrate TI Radar" from the
 * reference dashboard. The only wrapper features may use for a filled button
 * (invariant 5): never call `androidx.compose.material3.Button` directly.
 *
 * @param loading Shows a small spinner in place of [text] and disables the button,
 * for example while a club-selection request is in flight.
 */
@Composable
fun OfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(2.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text)
        }
    }
}

/**
 * Outlined secondary action button, for example the transport picker's neighboring
 * actions or a dismiss action. Use [OfButton] for the primary call to action.
 */
@Composable
fun OfOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
    ) {
        leadingContent?.invoke(this)
        Text(text)
    }
}

@Preview
@Composable
private fun OfButtonPreview() {
    OfTheme {
        OfCard(modifier = Modifier.padding(OfSpacing.Md)) {
            OfButton(text = "Retry", onClick = {}, modifier = Modifier.padding(OfSpacing.Sm))
            OfButton(
                text = "Applying",
                onClick = {},
                loading = true,
                modifier = Modifier.padding(OfSpacing.Sm),
            )
            OfOutlinedButton(
                text = "Calibrate TI Radar",
                onClick = {},
                modifier = Modifier.padding(OfSpacing.Sm),
            )
        }
    }
}
