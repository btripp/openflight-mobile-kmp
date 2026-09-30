// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

/// The filled primary button, the SwiftUI counterpart of Android's `OfButton`: dark text
/// (`Theme.bgDeep`) on gold (`Theme.gold`, about 9.4:1), where `.borderedProminent` put white
/// text on gold (about 2.1:1, issue #82).
///
/// - Pressed: the fill darkens to `Theme.goldDim` (bgDeep on goldDim is about 6.0:1).
/// - Disabled: a neutral `Theme.bgHover` fill with `Theme.creamDim` text (about 7.4:1), so the
///   label stays readable while the button clearly isn't gold, instead of the system's faded
///   tint.
///
/// `fill` defaults to gold; another light accent (Calibration's green Apply) keeps the same
/// dark-on-fill treatment. The padding follows `controlSize`, close to `.borderedProminent`'s,
/// so adopting the style doesn't change a screen's layout.
struct OfProminentButtonStyle: ButtonStyle {
    var fill: Color = Theme.gold
    var pressedFill: Color = Theme.goldDim

    func makeBody(configuration: Configuration) -> some View {
        ProminentBody(configuration: configuration, fill: fill, pressedFill: pressedFill)
    }

    private struct ProminentBody: View {
        let configuration: ButtonStyleConfiguration
        let fill: Color
        let pressedFill: Color
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.controlSize) private var controlSize

        var body: some View {
            configuration.label
                .foregroundStyle(isEnabled ? Theme.bgDeep : Theme.creamDim)
                .tint(isEnabled ? Theme.bgDeep : Theme.creamDim)
                .padding(.horizontal, padding.horizontal)
                .padding(.vertical, padding.vertical)
                .background(background, in: Capsule())
                .contentShape(Capsule())
                .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
        }

        private var background: Color {
            guard isEnabled else { return Theme.bgHover }
            return configuration.isPressed ? pressedFill : fill
        }

        private var padding: (horizontal: CGFloat, vertical: CGFloat) {
            switch controlSize {
            case .mini: (8, 3)
            case .small: (10, 5)
            case .large: (20, 14)
            case .extraLarge: (24, 18)
            default: (14, 7)
            }
        }
    }
}

extension ButtonStyle where Self == OfProminentButtonStyle {
    /// The gold primary button: dark text on gold (issue #82). Use it instead of
    /// `.buttonStyle(.borderedProminent).tint(Theme.gold)`.
    static var goldProminent: OfProminentButtonStyle { OfProminentButtonStyle() }

    /// The same filled button on another light accent (`Theme.success` for Calibration's Apply).
    static func ofProminent(_ fill: Color) -> OfProminentButtonStyle {
        OfProminentButtonStyle(fill: fill, pressedFill: fill.opacity(0.75))
    }
}
