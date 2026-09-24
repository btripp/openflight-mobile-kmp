import SwiftUI
import Shared

/// Placeholder until step R2 ports the SwiftUI screens (ADR 0001). The text comes from Kotlin,
/// which proves `Shared.framework` links and runs.
struct ContentView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text(AppInfo.shared.TITLE)
                .font(.largeTitle.bold())
            Text(AppInfoKt.platformSubtitle())
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}
