// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable

/**
 * The window's width class (Material 3 window size classes), which picks between phone and tablet
 * layouts: a bottom bar or a navigation rail ([OfAdaptiveScaffold]), one pane or two
 * ([OfListDetailPane]). Screens read it with [rememberOfWindowClass], and tests pass one in directly.
 */
enum class OfWindowClass {
    /** Under 600 dp wide: a phone in portrait. */
    COMPACT,

    /** 600 dp to under 840 dp: a tablet in portrait, a foldable unfolded, a phone in landscape. */
    MEDIUM,

    /** 840 dp and wider: a tablet in landscape, a desktop window. */
    EXPANDED,
    ;

    companion object {
        /** The lower bound of [MEDIUM], in dp (Material 3 breakpoint). */
        const val MEDIUM_MIN_WIDTH_DP = 600

        /** The lower bound of [EXPANDED], in dp (Material 3 breakpoint). */
        const val EXPANDED_MIN_WIDTH_DP = 840

        /** The class for a window [widthDp] wide. */
        fun fromWidthDp(widthDp: Int): OfWindowClass =
            when {
                widthDp >= EXPANDED_MIN_WIDTH_DP -> EXPANDED
                widthDp >= MEDIUM_MIN_WIDTH_DP -> MEDIUM
                else -> COMPACT
            }
    }
}

/**
 * The current window's [OfWindowClass], from `currentWindowAdaptiveInfoV2()` (the non-deprecated
 * form of `currentWindowAdaptiveInfo()`, whose large and extra-large widths map to [EXPANDED][OfWindowClass.EXPANDED]).
 * Updates on resize, rotation and multi-window changes.
 */
@Composable
fun rememberOfWindowClass(): OfWindowClass =
    OfWindowClass.fromWidthDp(currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp)
