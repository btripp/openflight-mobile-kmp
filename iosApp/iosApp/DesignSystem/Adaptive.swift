// SPDX-License-Identifier: AGPL-3.0-or-later
import SwiftUI

// The SwiftUI counterparts of `core:designsystem`'s `OfContentWidth` / `OfListDetailPane` (plan
// F1a/F1c): every screen reads `horizontalSizeClass` directly (there's no Compose Multiplatform
// here, so there's no shared `OfWindowClass`), and `.regular` is this app's one "tablet" case.

/// The widest a form or a column of text gets on a tablet before it's centered (matches Android's
/// `OfContentMaxWidth`, 840 dp).
let AppContentMaxWidth: CGFloat = 840

/// Centers `content` horizontally and caps its width at `maxWidth` on a `.regular`
/// `horizontalSizeClass`, so forms and text stay readable on an iPad instead of stretching edge to
/// edge. On a compact width it's a no-op: the content fills the width as before.
struct ContentWidth: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    var maxWidth: CGFloat = AppContentMaxWidth

    func body(content: Content) -> some View {
        if horizontalSizeClass == .regular {
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                content.frame(maxWidth: maxWidth)
                Spacer(minLength: 0)
            }
        } else {
            content
        }
    }
}

extension View {
    func contentWidth(_ maxWidth: CGFloat = AppContentMaxWidth) -> some View {
        modifier(ContentWidth(maxWidth: maxWidth))
    }
}

/// Which panes an [AppListDetailPane] shows (mirrors Android's `OfPaneLayout`).
enum AppPaneLayout {
    /// Only the list (single pane, nothing selected).
    case list
    /// Only the detail (single pane, an item selected).
    case detail
    /// The list and the detail side by side (two panes).
    case listAndDetail

    /// Two panes only on a `.regular` `horizontalSizeClass`. A compact width shows one pane: the
    /// detail when `hasSelection`, otherwise the list.
    static func of(isRegular: Bool, hasSelection: Bool) -> AppPaneLayout {
        if isRegular { return .listAndDetail }
        return hasSelection ? .detail : .list
    }
}

/// Accessibility identifiers for [AppListDetailPane]'s panes, so XCUITests can find them the same
/// way `OfListDetailPaneTags` lets Android device tests find `OfListDetailPane`'s.
enum AppListDetailTags {
    static let list = "app.listDetail.list"
    static let detail = "app.listDetail.detail"
}

/// A list and a detail (plan F1c): one pane on an iPhone or an iPad in a compact split (the detail
/// replaces the list when `hasSelection`), both side by side on a `.regular` width, the list taking
/// a fixed `listWidth`. The caller owns the selection and the back behaviour; on a single pane it
/// should clear the selection on back, and on two panes `detail` shows a placeholder when nothing
/// is selected.
struct AppListDetailPane<ListContent: View, DetailContent: View>: View {
    let hasSelection: Bool
    @ViewBuilder let list: () -> ListContent
    @ViewBuilder let detail: () -> DetailContent
    var listWidth: CGFloat = 360

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var isRegular: Bool { horizontalSizeClass == .regular }

    var body: some View {
        switch AppPaneLayout.of(isRegular: isRegular, hasSelection: hasSelection) {
        case .list:
            list()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier(AppListDetailTags.list)

        case .detail:
            detail()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier(AppListDetailTags.detail)

        case .listAndDetail:
            HStack(spacing: 0) {
                list()
                    .frame(width: listWidth, alignment: .topLeading)
                    .accessibilityIdentifier(AppListDetailTags.list)
                Divider()
                detail()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .accessibilityIdentifier(AppListDetailTags.detail)
            }
        }
    }
}

/// A header row: a title with its buttons (or pills) at the trailing edge (issue #81). At an
/// accessibility text size (AX1–AX5) one row squeezes the title into a narrow column, so words
/// break mid-word and a label stacks one letter per line; there the buttons move under the title,
/// on one row if they fit and stacked if they don't. At the other sizes it's the plain `HStack`
/// with a `Spacer` it replaces, so the default layout doesn't change.
struct AdaptiveHeaderRow<Title: View, Actions: View>: View {
    var alignment: VerticalAlignment = .center
    var spacing: CGFloat?
    @ViewBuilder let title: () -> Title
    @ViewBuilder let actions: () -> Actions

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 12) {
                title()
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: spacing) { actions() }
                    VStack(alignment: .leading, spacing: spacing) { actions() }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: alignment, spacing: spacing) {
                title()
                Spacer()
                actions()
            }
        }
    }
}
