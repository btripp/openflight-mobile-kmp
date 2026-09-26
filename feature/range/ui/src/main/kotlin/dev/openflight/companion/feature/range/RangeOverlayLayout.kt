// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.designsystem.OfWindowClass

/** Where the range's metrics live: overlaid on the scene, or docked to the side (plan F1b). */
internal enum class RangeOverlayLayout {
    OVERLAID,
    DOCKED,
    ;

    companion object {
        /**
         * Docked only on an expanded window in landscape (a tablet turned sideways): overlaying
         * translucent text on the scene works on a phone, but wastes a tablet's width and competes
         * with the ball's flight path for attention.
         */
        fun of(
            windowClass: OfWindowClass,
            isLandscape: Boolean,
        ): RangeOverlayLayout = if (windowClass == OfWindowClass.EXPANDED && isLandscape) DOCKED else OVERLAID
    }
}
