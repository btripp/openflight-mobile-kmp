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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Each slot is composed once and moved (not recreated) when the layout changes, for example when a
 * tablet rotates or a foldable unfolds between MEDIUM and EXPANDED, or the detail pane opens beside
 * the list: the list keeps its scroll position and both keep their remembered state.
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
    // The branches below place each slot at a different spot in the tree; invoking the slot
    // lambdas directly would dispose their state whenever the layout flips (compose-rules'
    // content-slot-reused). Movable content carries that state to its new spot. The latest lambdas
    // are read through rememberUpdatedState, so the movable content itself is created only once.
    val currentList by rememberUpdatedState(list)
    val currentDetail by rememberUpdatedState(detail)
    val listContent = remember { movableContentOf { currentList() } }
    val detailContent = remember { movableContentOf { currentDetail() } }
    when (OfPaneLayout.of(windowClass, hasSelection)) {
        OfPaneLayout.LIST -> {
            Box(modifier.fillMaxSize().testTag(OfListDetailPaneTags.LIST)) { listContent() }
        }

        OfPaneLayout.DETAIL -> {
            Box(modifier.fillMaxSize().testTag(OfListDetailPaneTags.DETAIL)) { detailContent() }
        }

        OfPaneLayout.LIST_AND_DETAIL -> {
            Row(modifier.fillMaxSize()) {
                Box(Modifier.weight(listFraction).fillMaxHeight().testTag(OfListDetailPaneTags.LIST)) {
                    listContent()
                }
                VerticalDivider(color = OfColorTokens.BgElevated)
                Box(Modifier.weight(1f - listFraction).fillMaxHeight().testTag(OfListDetailPaneTags.DETAIL)) {
                    detailContent()
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
