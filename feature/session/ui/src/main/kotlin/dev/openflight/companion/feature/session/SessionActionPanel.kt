// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfConfirmDialog
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSpinner
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme

/**
 * A destructive action's outcome, in the page (plan R8f): a spinner while [SessionActionState.Pending],
 * then what happened with OK, or why it failed with Try again. Announced by TalkBack as it changes
 * (a polite live region). Nothing for [SessionActionState.Idle] or a confirmation.
 */
@Composable
internal fun SessionActionPanel(
    state: SessionActionState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SessionActionState.Pending -> {
            OfCard(modifier = modifier.fillMaxWidth().testTag(SessionActionTestTags.PENDING)) {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md),
                ) {
                    OfSpinner(modifier = Modifier.size(20.dp))
                    OfText(text = state.message, role = OfTextRole.Body)
                }
            }
        }

        is SessionActionState.Done -> {
            OfCard(modifier = modifier.fillMaxWidth().testTag(SessionActionTestTags.DONE)) {
                OfText(
                    text = state.message,
                    role = OfTextRole.Body,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                OfOutlinedButton(
                    text = SessionActionCopy.DISMISS,
                    onClick = onDismiss,
                    modifier = Modifier.testTag(SessionActionTestTags.DISMISS),
                )
            }
        }

        is SessionActionState.Failed -> {
            OfCard(modifier = modifier.fillMaxWidth().testTag(SessionActionTestTags.FAILED)) {
                Column(
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
                ) {
                    // Not colour alone: the title says it failed.
                    OfText(text = state.title, role = OfTextRole.TitleSmall, color = OfColorTokens.Warning)
                    OfText(text = state.message, role = OfTextRole.BodySmall, color = OfColorTokens.CreamDim)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(OfSpacing.Sm),
                ) {
                    if (state.canRetry) {
                        OfButton(
                            text = SessionActionCopy.RETRY,
                            onClick = onRetry,
                            modifier = Modifier.testTag(SessionActionTestTags.RETRY),
                        )
                    }
                    OfOutlinedButton(
                        text = SessionActionCopy.DISMISS,
                        onClick = onDismiss,
                        modifier = Modifier.testTag(SessionActionTestTags.DISMISS),
                    )
                }
            }
        }

        is SessionActionState.Confirming, SessionActionState.Idle -> {
            Unit
        }
    }
}

/** The confirmation for a [SessionActionState.Confirming] state; nothing otherwise. */
@Composable
internal fun SessionActionDialog(
    state: SessionActionState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state !is SessionActionState.Confirming) return
    OfConfirmDialog(
        title = state.title,
        message = state.message,
        confirmLabel = state.confirmLabel,
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onCancel,
        confirmTag = SessionActionTestTags.CONFIRM,
        dismissTag = SessionActionTestTags.CANCEL,
    )
}

@Preview
@Composable
private fun SessionActionPanelPreview() {
    val clear = SessionAction.ClearSession("ann", "Ann")
    OfTheme {
        Column(verticalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
            SessionActionPanel(SessionActionState.Pending(clear, SessionActionCopy.pending(clear)), {}, {})
            SessionActionPanel(SessionActionState.Done(clear, SessionActionCopy.done(clear)), {}, {})
            SessionActionPanel(
                SessionActionState.Failed(
                    clear,
                    SessionActionCopy.failedTitle(clear),
                    "The Pi didn't confirm the clear.",
                    true,
                ),
                {},
                {},
            )
        }
    }
}
