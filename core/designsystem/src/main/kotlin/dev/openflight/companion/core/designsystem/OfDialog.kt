// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview

/**
 * A text-only action, for example a dialog's "Cancel" or a bottom-bar entry. Use [OfButton] or
 * [OfOutlinedButton] for anything that should stand out.
 *
 * @param destructive paints the label in the danger accent, for example "Shut Down".
 */
@Composable
fun OfTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = if (destructive) OfColorTokens.Danger else MaterialTheme.colorScheme.primary,
            ),
    ) {
        Text(text)
    }
}

/**
 * A modal yes/no confirmation, for example "Clear session?" or the web UI's "Shut down
 * OpenFlight?" dialog (`App.tsx`). [onDismiss] runs for "Cancel", a tap outside and Back.
 *
 * @param destructive paints the confirm action in the danger accent.
 * @param confirmTag / [dismissTag] test tags for the two actions.
 */
@Composable
fun OfConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    confirmTag: String = "",
    dismissTag: String = "",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = OfColorTokens.BgElevated,
        titleContentColor = OfColorTokens.Cream,
        textContentColor = OfColorTokens.CreamDim,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            OfTextButton(
                text = confirmLabel,
                onClick = onConfirm,
                destructive = destructive,
                modifier = Modifier.testTag(confirmTag),
            )
        },
        dismissButton = {
            OfTextButton(text = dismissLabel, onClick = onDismiss, modifier = Modifier.testTag(dismissTag))
        },
    )
}

@Preview
@Composable
private fun OfConfirmDialogPreview() {
    OfTheme {
        OfConfirmDialog(
            title = "Shut down OpenFlight?",
            message = "The Pi powers off. You'll need to switch it back on by hand.",
            confirmLabel = "Shut Down",
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}
