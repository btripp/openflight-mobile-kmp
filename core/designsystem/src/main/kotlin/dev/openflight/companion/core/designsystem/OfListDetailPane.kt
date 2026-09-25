// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Which panes an [OfListDetailPane] shows. */
enum class OfPaneLayout {
    /** Only the list (single pane, nothing selected). */
    LIST,

    /** Only the detail (single pane, an item selected). */
    DETAIL,

    /** The list and the detail side by side (two panes). */
    LIST_AND_DETAIL,
    ;

    companion object {
        /**
         * Two panes only on an [OfWindowClass.EXPANDED] window. Narrower windows show one pane: the
         * detail when [hasSelection], otherwise the list.
         */
        fun of(
            windowClass: OfWindowClass,
            hasSelection: Boolean,
        ): OfPaneLayout =
            when {
                windowClass == OfWindowClass.EXPANDED -> LIST_AND_DETAIL
                hasSelection -> DETAIL
                else -> LIST
            }
    }
}

/** Test tags for [OfListDetailPane]'s panes. */
object OfListDetailPaneTags {
    const val LIST = "of.listDetail.list"
    const val DETAIL = "of.listDetail.detail"
}

/**
 * A list and a detail: one pane on phones and tablets in portrait (the detail replaces the list
 * when [hasSelection]), both side by side on an expanded window, the list taking [listFraction] of
 * the width. The caller owns the selection and the back behaviour; on a single pane it should
 * clear the selection on back, and on two panes the [detail] shows a placeholder when nothing is
 * selected.
 *
 * @param windowClass injectable for tests and previews; defaults to [rememberOfWindowClass].
 */
@Composable
fun OfListDetailPane(
    hasSelection: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowClass: OfWindowClass = rememberOfWindowClass(),
    listFraction: Float = DEFAULT_LIST_FRACTION,
) {
    when (OfPaneLayout.of(windowClass, hasSelection)) {
        OfPaneLayout.LIST -> {
            Box(modifier.fillMaxSize().testTag(OfListDetailPaneTags.LIST)) { list() }
        }

        OfPaneLayout.DETAIL -> {
            Box(modifier.fillMaxSize().testTag(OfListDetailPaneTags.DETAIL)) { detail() }
        }

        OfPaneLayout.LIST_AND_DETAIL -> {
            Row(modifier.fillMaxSize()) {
                Box(Modifier.weight(listFraction).fillMaxHeight().testTag(OfListDetailPaneTags.LIST)) { list() }
                VerticalDivider(color = OfColorTokens.BgElevated)
                Box(Modifier.weight(1f - listFraction).fillMaxHeight().testTag(OfListDetailPaneTags.DETAIL)) {
                    detail()
                }
            }
        }
    }
}

private const val DEFAULT_LIST_FRACTION = 0.4f

@Composable
private fun PreviewPanes(windowClass: OfWindowClass) {
    OfTheme {
        OfScaffold { padding ->
            OfListDetailPane(
                hasSelection = true,
                windowClass = windowClass,
                modifier = Modifier.padding(padding),
                list = {
                    OfCard(modifier = Modifier.width(280.dp).padding(OfSpacing.Lg)) {
                        OfText(text = "Sessions", role = OfTextRole.Title)
                    }
                },
                detail = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        OfText(text = "Session detail", role = OfTextRole.Title)
                    }
                },
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 700)
@Composable
private fun OfListDetailPaneCompactPreview() = PreviewPanes(OfWindowClass.COMPACT)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OfListDetailPaneExpandedPreview() = PreviewPanes(OfWindowClass.EXPANDED)
