// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** Semantic text roles, so feature code never reaches into Material3 typography. */
enum class OfTextRole {
    Display,
    Headline,
    Title,
    TitleSmall,
    Body,
    BodySmall,
    Label,
    Eyebrow,
}

@Composable
private fun OfTextRole.style(): TextStyle =
    when (this) {
        OfTextRole.Display -> MaterialTheme.typography.displayMedium
        OfTextRole.Headline -> MaterialTheme.typography.headlineMedium
        OfTextRole.Title -> MaterialTheme.typography.titleLarge
        OfTextRole.TitleSmall -> MaterialTheme.typography.titleMedium
        OfTextRole.Body -> MaterialTheme.typography.bodyLarge
        OfTextRole.BodySmall -> MaterialTheme.typography.bodyMedium
        OfTextRole.Label -> MaterialTheme.typography.labelMedium
        OfTextRole.Eyebrow -> OfEyebrowTextStyle
    }

/**
 * Themed text. [color] defaults to the theme's on-background color; pass an
 * [OfColorTokens] value for emphasis (for example gold or a status tone).
 */
@Composable
fun OfText(
    text: String,
    modifier: Modifier = Modifier,
    role: OfTextRole = OfTextRole.Body,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = role.style(),
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onBackground else color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Themed icon; [contentDescription] is required for accessibility unless purely decorative (null). */
@Composable
fun OfIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = OfColorTokens.Cream,
) {
    Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)
}

/** Hairline divider between list rows or sections. */
@Composable
fun OfDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = OfColorTokens.BgElevated)
}

/** Determinate progress bar, e.g. calibration sample collection. [progress] is clamped to 0..1. */
@Composable
fun OfLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = OfColorTokens.Gold,
        trackColor = OfColorTokens.BgElevated,
    )
}

/** Indeterminate spinner for in-flight work (connecting, submitting). */
@Composable
fun OfSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier, color = OfColorTokens.Gold)
}
