// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The screens the dashboard navigates to.
enum AppRoute: Hashable {
    case calibration
    case range
}

/// The app's top-level sections (plan R5b/R6c), the same set and order as Android's
/// `TopLevelDestination` (`AppNavHost.kt`): a native tab bar on a compact width, where Android
/// shows its bottom bar; a `NavigationSplitView` sidebar on a regular width (plan F1c), where
/// Android shows a navigation rail.
enum AppTab: Hashable, CaseIterable {
    case dashboard, session, training, camera, settings

    var label: String {
        switch self {
        case .dashboard: "Dashboard"
        case .session: "Session"
        case .training: "Training"
        case .camera: "Camera"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .dashboard: "gauge.with.dots.needle.67percent"
        case .session: "list.bullet.rectangle"
        case .training: "speedometer"
        case .camera: "camera"
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

/// The app shell: Dashboard, Session, Training, Camera and Settings. The dashboard destination
/// keeps its own `NavigationStack` for Calibrate and the full-screen Range, as before.
///
/// Plan F1c: a `TabView` on a compact `horizontalSizeClass` (an iPhone, or an iPad in a compact
/// split), and a `NavigationSplitView` sidebar of the same destinations on a regular width (an
/// iPad, full screen or in a wide split). Pushed routes (Range, Calibration, the session history)
/// stay pushed on top of whichever shell is showing, exactly as before.
struct AppRoot: View {
    let launchOptions: LaunchOptions
    @State private var path: [AppRoute]
    @State private var tab: AppTab = .dashboard
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
    }
}

/// One `AppTab`'s root content, its own `NavigationStack` around it. Only the dashboard uses the
/// shared `path` (for Calibrate, the full-screen Range and `--range-mode`); the others push their
/// own destinations declaratively (`SessionView`'s "History" link).
private struct TabRootView: View {
    let tab: AppTab
    let launchOptions: LaunchOptions
    @Binding var path: [AppRoute]

    var body: some View {
        switch tab {
        case .dashboard:
            NavigationStack(path: $path) {
                DashboardView()
                    .navigationDestination(for: AppRoute.self) { route in
                        switch route {
                        case .calibration:
                            CalibrationView()
                                .toolbar(.hidden, for: .tabBar)
                        case .range:
                            // Full screen, like the reference: no navigation or tab bar.
                            DrivingRangeView(autoplay: launchOptions.previewFlight)
                                .toolbar(.hidden, for: .tabBar)
                        }
                    }
            }
        case .session:
            NavigationStack { SessionView() }
        case .training:
            NavigationStack { TrainingView() }
        case .camera:
            NavigationStack { CameraView() }
        case .settings:
            NavigationStack { SettingsView() }
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

    /// `List(selection:)` wants an optional binding; a `nil` selection can't happen here; the
    /// sidebar always has exactly one of `AppTab` selected.
    private var selection: Binding<AppTab?> {
        Binding(get: { tab }, set: { newValue in if let newValue { tab = newValue } })
    }

    var body: some View {
        NavigationSplitView {
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
