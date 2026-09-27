// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The pushed (full-screen, bar-less) screens. Practice pushes Calibrate, Range and Speed training;
/// Settings' Device group pushes Calibrate and Camera (plan F1d).
enum AppRoute: Hashable {
    case calibration
    case range
    case training
    case camera
    /// Plan F8d-B: Practice's "View on range", opened on that shot.
    case rangeAt(RangeTarget)

    /// Either range route: the iPad sidebar collapses under both.
    var isRange: Bool {
        switch self {
        case .range, .rangeAt: true
        default: false
        }
    }
}

/// The app's top-level destinations (plan F1d): Practice · Sessions · Bag · Settings, the same
/// labels and order as Android's `TopLevelDestination` (`AppNavHost.kt`) on every form factor. A
/// native tab bar on a compact width, where Android shows its bottom bar; a `NavigationSplitView`
/// sidebar on a regular width (plan F1c), where Android shows a navigation rail. F9b/F9c insert
/// Play at index 1. Four (later five) entries never overflow into the iPhone's "More" tab.
enum AppTab: Hashable, CaseIterable {
    case practice, sessions, bag, settings

    var label: String {
        switch self {
        case .practice: "Practice"
        case .sessions: "Sessions"
        case .bag: "Bag"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .practice: "gauge.with.dots.needle.67percent"
        case .sessions: "list.bullet.rectangle"
        case .bag: "bag"
        case .settings: "gearshape"
        }
    }
}

/// Accessibility identifiers for the app shell's top-level navigation (the tab bar or the
/// sidebar), the SwiftUI counterpart of Android's `AppNavTags`.
enum AppNavigationTags {
    static let sidebar = "app.nav.sidebar"

    static func sidebarItem(_ tab: AppTab) -> String { "app.nav.sidebar.\(tab)" }
}

/// The app shell: Practice, Sessions, Bag and Settings (plan F1d). Practice keeps the shared `path`
/// for Calibrate, Speed training and the full-screen Range (and `--range-mode`).
///
/// Plan F1c: a `TabView` on a compact `horizontalSizeClass` (an iPhone, or an iPad in a compact
/// split), and a `NavigationSplitView` sidebar of the same destinations on a regular width (an
/// iPad, full screen or in a wide split). Pushed routes stay pushed on top of whichever shell is
/// showing. Plan F1d: while the Range is up, the sidebar collapses, so it's truly full screen.
struct AppRoot: View {
    let launchOptions: LaunchOptions
    @State private var path: [AppRoute]
    @State private var tab: AppTab = .practice
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(launchOptions: LaunchOptions) {
        self.launchOptions = launchOptions
        // `--range-mode` opens the driving range over the dashboard (ContentView.swift:33-35).
        _path = State(initialValue: launchOptions.rangeMode ? [.range] : [])
    }

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                AppSidebarShell(launchOptions: launchOptions, path: $path, tab: $tab)
            } else {
                AppTabShell(launchOptions: launchOptions, path: $path, tab: $tab)
            }
        }
        .tint(Theme.gold)
        .font(.of(.body))
        .preferredColorScheme(.dark)
        // Plan F8d-B: "View on range" from any tab opens the range full screen over it.
        .rangePresenter()
    }
}

/// A pushed `AppRoute`'s screen. Each one hides the tab bar, as Android's pushed routes hide the
/// bottom bar or rail.
private struct AppRouteDestination: View {
    let route: AppRoute
    let launchOptions: LaunchOptions

    var body: some View {
        switch route {
        case .calibration:
            CalibrationView()
                .toolbar(.hidden, for: .tabBar)
        case .range:
            // Full screen, like the reference: no navigation or tab bar.
            DrivingRangeView(autoplay: launchOptions.previewFlight)
                .toolbar(.hidden, for: .tabBar)
        case let .rangeAt(target):
            DrivingRangeView(autoplay: false, launch: target.launch)
                .toolbar(.hidden, for: .tabBar)
        case .training:
            TrainingView()
                .toolbar(.hidden, for: .tabBar)
        case .camera:
            CameraView()
                .toolbar(.hidden, for: .tabBar)
        }
    }
}

/// One `AppTab`'s root content, its own `NavigationStack` around it. Only Practice uses the shared
/// `path` (for Calibrate, Speed training, the full-screen Range and `--range-mode`); Settings
/// pushes its Device screens on its own stack, and Sessions and Bag push their own destinations
/// declaratively (`SessionView`'s "History" link).
private struct TabRootView: View {
    let tab: AppTab
    let launchOptions: LaunchOptions
    @Binding var path: [AppRoute]

    var body: some View {
        switch tab {
        case .practice:
            NavigationStack(path: $path) {
                DashboardView(onOpenTraining: { path.append(.training) })
                    .rangePusher(path: $path)
                    .navigationDestination(for: AppRoute.self) { route in
                        AppRouteDestination(route: route, launchOptions: launchOptions)
                    }
            }
        case .sessions:
            NavigationStack { SessionView() }
        case .bag:
            // Plan F5: My Bag.
            NavigationStack { BagView() }
        case .settings:
            NavigationStack {
                SettingsView()
                    .navigationDestination(for: AppRoute.self) { route in
                        AppRouteDestination(route: route, launchOptions: launchOptions)
                    }
            }
        }
    }
}

/// The compact shell: a native `TabView`, exactly as before F1c.
private struct AppTabShell: View {
    let launchOptions: LaunchOptions
    @Binding var path: [AppRoute]
    @Binding var tab: AppTab

    var body: some View {
        TabView(selection: $tab) {
            ForEach(AppTab.allCases, id: \.self) { entry in
                TabRootView(tab: entry, launchOptions: launchOptions, path: $path)
                    .tabItem { Label(entry.label, systemImage: entry.systemImage) }
                    .tag(entry)
            }
        }
    }
}

/// The regular-width shell (plan F1c): a `NavigationSplitView` sidebar of the same destinations,
/// where Android shows a navigation rail (plan F1a).
private struct AppSidebarShell: View {
    let launchOptions: LaunchOptions
    @Binding var path: [AppRoute]
    @Binding var tab: AppTab
    /// The visibility the user (or the system) last chose, kept apart from the Range's override.
    @State private var chosenVisibility: NavigationSplitViewVisibility = .automatic

    /// `List(selection:)` wants an optional binding; a `nil` selection can't happen here; the
    /// sidebar always has exactly one of `AppTab` selected.
    private var selection: Binding<AppTab?> {
        Binding(get: { tab }, set: { newValue in if let newValue { tab = newValue } })
    }

    /// Plan F1d: the Range is full screen on an iPad too, so the sidebar collapses while it's on
    /// Practice's stack (a `--range-mode` launch included) and comes back once it's popped.
    private var showsRange: Bool { tab == .practice && path.contains(where: \.isRange) }

    /// `.detailOnly` for as long as the Range is up, whatever the split view writes back meanwhile
    /// (it resets the visibility when it first lays out, which a one-off `onChange` would lose on a
    /// `--range-mode` launch); otherwise the chosen visibility.
    private var columnVisibility: Binding<NavigationSplitViewVisibility> {
        Binding(
            get: { showsRange ? .detailOnly : chosenVisibility },
            set: { newValue in if !showsRange { chosenVisibility = newValue } }
        )
    }

    var body: some View {
        NavigationSplitView(columnVisibility: columnVisibility) {
            List(selection: selection) {
                ForEach(AppTab.allCases, id: \.self) { entry in
                    Label(entry.label, systemImage: entry.systemImage)
                        .tag(entry)
                        // One element, not a separate icon and text, so the whole row is what
                        // XCUITest taps and finds by this identifier.
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier(AppNavigationTags.sidebarItem(entry))
                }
            }
            .listStyle(.sidebar)
            .navigationTitle("OpenFlight")
            .accessibilityIdentifier(AppNavigationTags.sidebar)
        } detail: {
            TabRootView(tab: tab, launchOptions: launchOptions, path: $path)
        }
        .navigationSplitViewStyle(.balanced)
    }
}

/// The dark gradient behind every tab's content, under a transparent navigation bar.
struct ScreenBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Theme.background.ignoresSafeArea())
            .foregroundStyle(Theme.cream)
    }
}

extension View {
    func screenBackground() -> some View { modifier(ScreenBackground()) }
}
