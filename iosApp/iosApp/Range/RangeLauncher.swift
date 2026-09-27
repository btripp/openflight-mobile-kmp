// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Plan F8d-B: where "View on range" opens the driving range: a stored session, paused on one of
/// its shots when `shotId` names one. It becomes the shared `RangeLaunch`, which the range's view
/// model resolves (a row id, an event id or a Pi timestamp; other sessions are searched when the
/// shot isn't in this one).
struct RangeTarget: Identifiable, Hashable {
    let sessionId: String
    var shotId: String?

    var id: String { "\(sessionId)|\(shotId ?? "")" }

    var launch: RangeLaunch { RangeLaunch(sessionId: sessionId, shotId: shotId) }

    /// A live shot: filed under the current history session, like Android's `viewLiveShotOnRange`.
    static func liveShot(_ shotId: String) -> RangeTarget {
        RangeTarget(sessionId: KoinHelper().currentHistorySessionId() ?? "", shotId: shotId)
    }
}

/// Opens the range on a `RangeTarget`: SwiftUI's counterpart of Android's nullable `onViewOnRange`
/// lambdas. `nil` in the environment (previews, tests of a lone screen) hides the buttons.
///
/// One instance for the app's lifetime, so the environment value never changes: a fresh closure per
/// update made every reader (the dashboard's lazy stack included) update again, in a loop, once the
/// range was up.
@Observable
final class OpenRangeAction {
    /// The range on screen, if any (the presenter's `fullScreenCover` item).
    var target: RangeTarget?

    func callAsFunction(_ target: RangeTarget) {
        self.target = target
    }
}

private struct OpenRangeKey: EnvironmentKey {
    static let defaultValue: OpenRangeAction? = nil
}

extension EnvironmentValues {
    /// Plan F8d-B: set by the app shell (`rangePresenter()`).
    var openRange: OpenRangeAction? {
        get { self[OpenRangeKey.self] }
        set { self[OpenRangeKey.self] = newValue }
    }
}

/// Accessibility identifiers for the "View on range" buttons. The dashboard's and the bag's are the
/// shared `DashboardTestTags`/`BagTestTags`; the session ones mirror Android's (`SessionTestTags`
/// lives in the Android-only `feature:session:ui`).
enum RangeEntryTags {
    /// The selected-shot card's button.
    static let selectedShot = "session.selected.viewOnRange"
    /// The stored-session detail's "Replay on range".
    static let replaySession = SessionHistoryTestTags.shared.DETAIL_REPLAY_ON_RANGE

    /// A session shot row's button, by its `SessionShotRow.id`.
    static func sessionShot(_ id: String) -> String { "session.shot.\(id).viewOnRange" }
}

/// Presents the range full screen over whichever tab asked (Practice, Sessions, Bag), so it covers
/// the tab bar on an iPhone and the sidebar on an iPad (plan F1d), and Exit returns to that screen.
private struct RangePresenter: ViewModifier {
    @State private var action = OpenRangeAction()
    /// Plan F14: the Demo badge covers the full-screen range too.
    @StateObject private var demoMode = DemoModeObserver()

    func body(content: Content) -> some View {
        @Bindable var action = action
        content
            .environment(\.openRange, action)
            .fullScreenCover(item: $action.target) { target in
                DrivingRangeView(autoplay: false, launch: target.launch)
                    .demoBanner(demoMode.enabled)
                    .tint(Theme.gold)
                    .font(.of(.body))
                    .preferredColorScheme(.dark)
            }
    }
}

/// Practice's "View on range" pushes the range on Practice's own stack instead (`AppRoute.rangeAt`),
/// like its Range button. Presented over the dashboard instead, the live dashboard kept laying out
/// under the cover and the main thread never went idle.
private struct RangePusher: ViewModifier {
    @Binding var path: [AppRoute]
    @State private var action = OpenRangeAction()

    func body(content: Content) -> some View {
        content
            .environment(\.openRange, action)
            .onChange(of: action.target) { _, target in
                guard let target else { return }
                action.target = nil
                path.append(.rangeAt(target))
            }
    }
}

extension View {
    /// Plan F8d-B: lets every screen below open the range with `@Environment(\.openRange)`.
    func rangePresenter() -> some View { modifier(RangePresenter()) }

    /// Plan F8d-B: like `rangePresenter()`, but pushes the range onto `path`.
    func rangePusher(path: Binding<[AppRoute]>) -> some View { modifier(RangePusher(path: path)) }
}

/// The "View on range" button (Android's `OfTextButton("View on range")`/`("Range")`), padded to a
/// 44 pt target. `iconOnly` for shot rows and the selected-shot card, whose width an iPhone needs
/// for the metrics; VoiceOver still reads "View on range".
struct ViewOnRangeButton: View {
    var iconOnly = false
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("View on range", systemImage: "scope")
                .labelStyle(RangeButtonLabelStyle(iconOnly: iconOnly))
                .font(.of(.subheadline, weight: .semibold))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .tint(Theme.gold)
        .accessibilityLabel("View on range")
        .accessibilityIdentifier(identifier)
    }
}

private struct RangeButtonLabelStyle: LabelStyle {
    let iconOnly: Bool

    func makeBody(configuration: Configuration) -> some View {
        if iconOnly {
            configuration.icon
        } else {
            HStack(spacing: 4) {
                configuration.icon
                configuration.title
            }
        }
    }
}
