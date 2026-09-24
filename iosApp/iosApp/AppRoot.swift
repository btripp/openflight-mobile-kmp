// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The screens the dashboard navigates to.
enum AppRoute: Hashable {
    case calibration
    case range
}

/// The app shell: a `NavigationStack` rooted at the dashboard.
struct AppRoot: View {
    let launchOptions: LaunchOptions
    @State private var path: [AppRoute]

    init(launchOptions: LaunchOptions) {
        self.launchOptions = launchOptions
        // `--range-mode` opens the driving range over the dashboard (ContentView.swift:33-35).
        _path = State(initialValue: launchOptions.rangeMode ? [.range] : [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            DashboardView()
                .navigationDestination(for: AppRoute.self) { route in
                    switch route {
                    case .calibration: CalibrationView()
                    // R3b replaces only the next line with its DrivingRangeView (launchOptions.previewFlight).
                    case .range: DrivingRangeView(autoplay: launchOptions.previewFlight)
                    }
                }
        }
        .tint(Theme.gold)
        .preferredColorScheme(.dark)
    }
}

/// A stand-in for a screen a later step ports (R3a calibration, R3b range).
struct PlaceholderScreen: View {
    let title: String
    let identifier: String

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            Text(title)
                .font(.title2.bold())
                .foregroundStyle(Theme.cream)
                .accessibilityIdentifier(identifier)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
