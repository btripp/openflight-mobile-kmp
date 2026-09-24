// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI
import UIKit

/// The OpenFlight type scale, the same fonts as Android's `core:designsystem` (plan R5b): DM Serif
/// Display for headings and Outfit (a variable font, weighted with `.weight`) for everything else.
/// Every size is tied to a Dynamic Type text style with `relativeTo:`, so text still scales with
/// the user's preferred size.
extension Font {
    /// The bundled font names (`UIAppFonts` in Info.plist).
    enum OpenFlight {
        static let display = "DMSerifDisplay-Regular"
        /// The variable font's PostScript name is its default instance's (Thin); `.weight` picks the
        /// weight on its `wght` axis.
        static let body = "Outfit-Thin"
    }

    /// A DM Serif Display heading at `style`'s default size, scaling with Dynamic Type.
    static func ofDisplay(_ style: Font.TextStyle, size: CGFloat? = nil) -> Font {
        .custom(OpenFlight.display, size: size ?? style.defaultPointSize, relativeTo: style)
    }

    /// Outfit body text at `style`'s default size and `weight`, scaling with Dynamic Type.
    static func of(_ style: Font.TextStyle, weight: Font.Weight = .regular, size: CGFloat? = nil) -> Font {
        .custom(OpenFlight.body, size: size ?? style.defaultPointSize, relativeTo: style).weight(weight)
    }

    /// The eyebrow above a card ("LATEST SHOT"): bold caption, tracked out by the caller.
    static let ofEyebrow = Font.of(.caption, weight: .bold)
}

extension Font.TextStyle {
    /// The text style's size at the default ("Large") Dynamic Type setting.
    var defaultPointSize: CGFloat {
        switch self {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline: 17
        case .body: 17
        case .callout: 16
        case .subheadline: 15
        case .footnote: 13
        case .caption: 12
        case .caption2: 11
        default: 17
        }
    }
}

enum AppearanceSetup {
    /// Navigation and tab bars in the OpenFlight fonts and palette. UIKit bars aren't styled by
    /// SwiftUI fonts, so this goes through the appearance proxies once at launch.
    @MainActor
    static func apply() {
        let metrics = { (style: UIFont.TextStyle, name: String, size: CGFloat, weight: CGFloat?) -> UIFont in
            var descriptor = UIFontDescriptor(name: name, size: size)
            if let weight {
                descriptor = descriptor.addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: weight]])
            }
            return UIFontMetrics(forTextStyle: style).scaledFont(for: UIFont(descriptor: descriptor, size: size))
        }
        let cream = UIColor(Theme.cream)

        let navigation = UINavigationBarAppearance()
        navigation.configureWithTransparentBackground()
        navigation.largeTitleTextAttributes = [
            .font: metrics(.largeTitle, Font.OpenFlight.display, 34, nil),
            .foregroundColor: cream,
        ]
        navigation.titleTextAttributes = [
            .font: metrics(.headline, Font.OpenFlight.body, 17, UIFont.Weight.semibold.rawValue),
            .foregroundColor: cream,
        ]
        let scrolled = navigation.copy()
        scrolled.configureWithDefaultBackground()
        scrolled.backgroundColor = UIColor(Theme.bgDeep).withAlphaComponent(0.92)
        scrolled.largeTitleTextAttributes = navigation.largeTitleTextAttributes
        scrolled.titleTextAttributes = navigation.titleTextAttributes
        UINavigationBar.appearance().standardAppearance = scrolled
        UINavigationBar.appearance().compactAppearance = scrolled
        UINavigationBar.appearance().scrollEdgeAppearance = navigation

        let tabs = UITabBarAppearance()
        tabs.configureWithDefaultBackground()
        tabs.backgroundColor = UIColor(Theme.bgDeep).withAlphaComponent(0.92)
        let tabFont = metrics(.caption2, Font.OpenFlight.body, 11, UIFont.Weight.medium.rawValue)
        for item in [tabs.stackedLayoutAppearance, tabs.inlineLayoutAppearance, tabs.compactInlineLayoutAppearance] {
            item.normal.titleTextAttributes = [.font: tabFont]
            item.selected.titleTextAttributes = [.font: tabFont]
        }
        UITabBar.appearance().standardAppearance = tabs
        UITabBar.appearance().scrollEdgeAppearance = tabs
    }
}
