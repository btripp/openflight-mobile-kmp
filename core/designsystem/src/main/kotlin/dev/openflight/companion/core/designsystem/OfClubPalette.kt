// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Per-club colours for charts, readable on [OfColorTokens.BgDeep] and [OfColorTokens.BgCard].
 * A chart numbers the clubs it shows (driver first) and asks for [color] by that number; past
 * eight clubs the hues repeat. Mirrored by `Theme.clubColors` on iOS.
 */
object OfClubPalette {
    val colors: List<Color> =
        listOf(
            Color(0xFF60A5FA), // blue
            Color(0xFF4ADE80), // green
            Color(0xFFF87171), // red
            Color(0xFFC084FC), // purple
            Color(0xFFFB923C), // orange
            Color(0xFF2DD4BF), // teal
            Color(0xFFF472B6), // pink
            Color(0xFFFACC15), // yellow
        )

    fun color(index: Int): Color = colors[index.mod(colors.size)]
}
