// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/**
 * A list row that deletes itself on an end-to-start swipe, revealing a red "Delete" background
 * while dragging. TalkBack users get the same action as a custom accessibility action named
 * [deleteLabel], since a swipe isn't reachable with a screen reader.
 *
 * The caller keys this composable by the row's id, so a deleted row's state never carries over to
 * the row that takes its place.
 */
@Composable
fun OfSwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deleteLabel: String = "Delete",
    content: @Composable RowScope.() -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.semantics {
                customActions = listOf(CustomAccessibilityAction(deleteLabel) { currentOnDelete().let { true } })
            },
        enableDismissFromStartToEnd = false,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) currentOnDelete() },
        backgroundContent = {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(OfColorTokens.Danger.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                        .padding(horizontal = OfSpacing.Lg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = deleteLabel, style = MaterialTheme.typography.labelLarge, color = OfColorTokens.BgDeep)
            }
        },
        content = content,
    )
}
