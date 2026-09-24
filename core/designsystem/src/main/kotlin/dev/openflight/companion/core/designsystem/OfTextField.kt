// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview

/**
 * A borderless text field over the app background, for example the Wi-Fi host field
 * on the dashboard and the calibration screen. Submits [onSubmit] on the keyboard's
 * "Go"/enter action rather than debouncing on every keystroke, matching the
 * reference's "apply on submit" host field.
 */
@Composable
fun OfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        textStyle =
            MaterialTheme.typography.bodyLarge.let {
                if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it
            },
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = OfColorTokens.BgElevated,
                unfocusedContainerColor = OfColorTokens.BgElevated,
                disabledContainerColor = OfColorTokens.BgElevated,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
        shape = MaterialTheme.shapes.small,
    )
}

@Preview
@Composable
private fun OfTextFieldPreview() {
    OfTheme {
        OfTextField(
            value = "raspberrypi.local:8080",
            onValueChange = {},
            placeholder = "raspberrypi.local:8080",
            monospace = true,
            modifier = Modifier.padding(OfSpacing.Md),
        )
    }
}
