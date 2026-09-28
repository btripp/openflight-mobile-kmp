// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

/**
 * A modal sheet from the bottom edge, for a short task over the current screen, for example the
 * dashboard's profile picker (plan R8f). It opens fully expanded; [onDismiss] runs for a swipe
 * down, a tap outside and Back. The content scrolls, so a large font scale never clips it.
 *
 * @param dimBehind false keeps what's behind the sheet undimmed (a tap outside still dismisses),
 *   for a sheet whose changes the user watches apply live, like the range's quick settings.
 * @param maxContentHeight caps the sheet's height (its content scrolls), so more of the screen
 *   behind it stays in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dimBehind: Boolean = true,
    maxContentHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = OfColorTokens.BgElevated,
        contentColor = OfColorTokens.Cream,
        scrimColor = if (dimBehind) BottomSheetDefaults.ScrimColor else Color.Transparent,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContentHeight)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Md),
            content = content,
        )
    }
}

@Preview
@Composable
private fun OfBottomSheetPreview() {
    OfTheme {
        OfBottomSheet(onDismiss = {}) {
            OfText(text = "Select profile", role = OfTextRole.Title)
            OfText(text = "Ann")
        }
    }
}
