// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The screens the dashboard navigates to.
enum AppRoute: Hashable {
    case calibration
    case range
}

/// The app's top-level sections (plan R5b/R6c): a native tab bar, where Android has its bottom bar.
enum AppTab: Hashable {
    case dashboard, session, training, camera, settings
}

/// The app shell: a `TabView` of Dashboard, Session, Training, Camera and Settings. The dashboard
/// tab keeps its own `NavigationStack` for Calibrate and the full-screen Range, as before.
struct AppRoot: View {
    let launchOptions: LaunchOptions
    @State private var path: [AppRoute]
    @State private var tab: AppTab = .dashboard

    init(launchOptions: LaunchOptions) {
        self.launchOptions = launchOptions
        // `--range-mode` opens the driving range over the dashboard (ContentView.swift:33-35).
        _path = State(initialValue: launchOptions.rangeMode ? [.range] : [])
    }

    var body: some View {
        TabView(selection: $tab) {
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
            .tabItem { Label("Dashboard", systemImage: "gauge.with.dots.needle.67percent") }
            .tag(AppTab.dashboard)

            NavigationStack { SessionView() }
                .tabItem { Label("Session", systemImage: "list.bullet.rectangle") }
                .tag(AppTab.session)

            NavigationStack { TrainingView() }
                .tabItem { Label("Training", systemImage: "speedometer") }
                .tag(AppTab.training)

            NavigationStack { CameraView() }
                .tabItem { Label("Camera", systemImage: "camera") }
                .tag(AppTab.camera)

            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(AppTab.settings)
        }
        .tint(Theme.gold)
        .font(.of(.body))
        .preferredColorScheme(.dark)
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
