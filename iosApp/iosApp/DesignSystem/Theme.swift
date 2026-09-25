// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// The OpenFlight palette for SwiftUI: the same values as Android's `core:designsystem`
/// `OfColorTokens` (gold on dark, from the upstream `docs/color_palette.html`). Views use these
/// tokens instead of system colors, so both platforms look the same.
enum Theme {
    // Backgrounds
    static let bgDeep = Color(hex: 0x0A0A0F)
    static let bgCard = Color(hex: 0x12121A)
    static let bgElevated = Color(hex: 0x1A1A24)
    static let bgHover = Color(hex: 0x222230)

    // Gold (primary)
    static let gold = Color(hex: 0xD4AF37)
    static let goldBright = Color(hex: 0xF4CF47)
    static let goldDim = Color(hex: 0xA68B2A)

    // Cream (text)
    static let cream = Color(hex: 0xF5F0E6)
    static let creamDim = Color(hex: 0xF5F0E6, opacity: 0.70)
    static let creamMuted = Color(hex: 0xF5F0E6, opacity: 0.50)

    // Accents (status)
    static let success = Color(hex: 0x4ADE80)
    static let info = Color(hex: 0x60A5FA)
    static let warning = Color(hex: 0xFBBF24)
    static let danger = Color(hex: 0xF87171)
    /// The idle/unknown status tone (the reference's `.gray`).
    static let neutral = Color(hex: 0x8A8A96)

    /// Per-club chart colours, the same values as Android's `OfClubPalette`. Charts number the
    /// clubs they show (driver first); past eight clubs the hues repeat.
    static let clubColors: [Color] = [
        Color(hex: 0x60A5FA), // blue
        Color(hex: 0x4ADE80), // green
        Color(hex: 0xF87171), // red
        Color(hex: 0xC084FC), // purple
        Color(hex: 0xFB923C), // orange
        Color(hex: 0x2DD4BF), // teal
        Color(hex: 0xF472B6), // pink
        Color(hex: 0xFACC15), // yellow
    ]

    static func clubColor(_ index: Int) -> Color {
        clubColors[((index % clubColors.count) + clubColors.count) % clubColors.count]
    }

    /// The screen background: the reference's diagonal gradient, in our palette.
    static let background = LinearGradient(
        colors: [bgElevated, bgDeep],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

extension Color {
    /// A color from a 0xRRGGBB literal.
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
