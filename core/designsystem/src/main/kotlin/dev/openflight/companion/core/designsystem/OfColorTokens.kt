// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw color tokens taken from the OpenFlight reference palette
 * (`docs/color_palette.html` in the upstream repo). The app is dark-first: these are
 * the values [OfTheme] builds its Material3 color scheme from, plus a handful of
 * status accents that Material3's `ColorScheme` has no slot for.
 */
object OfColorTokens {
    // Backgrounds
    val BgDeep = Color(0xFF0A0A0F)
    val BgCard = Color(0xFF12121A)
    val BgElevated = Color(0xFF1A1A24)
    val BgHover = Color(0xFF222230)

    // Gold (primary)
    val Gold = Color(0xFFD4AF37)
    val GoldBright = Color(0xFFF4CF47)
    val GoldDim = Color(0xFFA68B2A)

    // Cream (text)
    val Cream = Color(0xFFF5F0E6)
    val CreamDim = Color(0xB3F5F0E6)
    val CreamMuted = Color(0x80F5F0E6)

    // Accents (status)
    val Success = Color(0xFF4ADE80)
    val Info = Color(0xFF60A5FA)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)

    // Neutral, for the idle/unknown status tone. Not part of the reference palette,
    // matching ContentView's `.gray` idle indicator.
    val Neutral = Color(0xFF8A8A96)
}
