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
                    // R3a replaces only the next line with its CalibrationView.
                    case .calibration: PlaceholderScreen(title: "Calibrate TI Radar", identifier: "calibration.placeholder")
                    // R3b replaces only the next line with its DrivingRangeView (launchOptions.previewFlight).
                    case .range: PlaceholderScreen(title: "Driving Range", identifier: "range.placeholder")
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
