// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A status message with an icon and words, never colour alone (plan R8f accessibility pass): a
 * spinner while [busy], a check for [StatusTone.Positive], a warning triangle otherwise. The icon
 * and text merge into one screen-reader stop that is a polite live region, so TalkBack announces
 * it when it appears or its text changes (for example "Capturing radar data…" then "Calculating
 * metrics…"). [actions] go under the text and stay separately focusable.
 */
@Composable
fun OfNotice(
    title: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    detail: String? = null,
    busy: Boolean = false,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val color = tone.noticeColor()
    val reduceMotion = rememberOfReduceMotion()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
                .padding(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
    ) {
        Row(
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (detail != null) "$title. $detail" else title
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
        ) {
            when {
                // With animations removed, a still ring says "working" without spinning.
                busy && reduceMotion -> {
                    Box(
                        modifier =
                            Modifier
                                .size(22.dp)
                                .border(3.dp, OfColorTokens.Gold, CircleShape),
                    )
                }

                busy -> {
                    OfSpinner(modifier = Modifier.size(22.dp))
                }

                tone == StatusTone.Positive -> {
                    OfIcon(OfIcons.Check, contentDescription = null, tint = color)
                }

                else -> {
                    OfIcon(OfIcons.Warning, contentDescription = null, tint = color)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs)) {
                OfText(text = title, role = OfTextRole.TitleSmall)
                detail?.let { OfText(text = it, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim) }
            }
        }
        actions?.invoke(this)
    }
}

private fun StatusTone.noticeColor() =
    when (this) {
        StatusTone.Positive -> OfColorTokens.Success
        StatusTone.InProgress -> OfColorTokens.Warning
        StatusTone.Negative -> OfColorTokens.Danger
        StatusTone.Neutral -> OfColorTokens.Gold
    }

@Preview
@Composable
private fun OfNoticePreview() {
    OfTheme {
        Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm)) {
            OfNotice(
                title = "Impact detected",
                detail = "Capturing radar data…",
                tone = StatusTone.InProgress,
                busy = true,
            )
            OfNotice(title = "Address not allowed", detail = "Public addresses need HTTPS", tone = StatusTone.Negative)
            OfNotice(title = "OpenFlight stopped; the Pi stays on", tone = StatusTone.Positive) {
                OfTextButton(text = "OK", onClick = {})
            }
        }
    }
}
