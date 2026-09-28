// SPDX-License-Identifier: AGPL-3.0-or-later
import KMPNativeCoroutinesAsync
import Shared
import SwiftUI

/// Accessibility identifiers for the app shell's Demo mode chrome (plan F14), the SwiftUI
/// counterpart of Android's `AppChromeTags`.
enum DemoChromeTags {
    static let badge = "app.demoBadge"
}

/// Plan F14: whether Demo mode is on, followed for the app's lifetime (the shared
/// `DemoModeRepository`, through `KoinHelper().demoMode()`).
@MainActor
final class DemoModeObserver: ObservableObject {
    @Published private(set) var enabled: Bool
    private var task: Task<Void, Never>?

    init(bridge: DemoModeBridge = KoinHelper().demoMode()) {
        enabled = bridge.enabled
        let values = asyncSequence(for: bridge.enabledFlow)
        task = Task { [weak self] in
            do {
                for try await value in values {
                    self?.enabled = value.boolValue
                }
            } catch {
                // A StateFlow never fails; cancellation ends the loop.
            }
        }
    }

    deinit {
        task?.cancel()
    }
}

/// Plan F14: the persistent Demo badge: a gold strip above every screen while Demo mode is on, so
/// no made-up shot is ever taken for a measurement. It sits in the top safe-area inset, so the
/// screens under it lay out below it.
struct DemoBanner: View {
    var body: some View {
        Text("DEMO · MADE-UP SHOTS, NO PI")
            .font(.ofEyebrow)
            .tracking(1.7)
            .foregroundStyle(Theme.bgDeep)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .background(Theme.gold)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Demo mode is on. Shots are made up, not measured.")
            .accessibilityIdentifier(DemoChromeTags.badge)
    }
}

/// Plan F14: the small "Demo" tag on a made-up shot.
struct DemoTag: View {
    var body: some View {
        Text(ConnectionPanelState.companion.DEMO_TAG)
            .font(.of(.caption2, weight: .bold))
            .foregroundStyle(Theme.bgDeep)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Theme.warning, in: Capsule())
            .accessibilityLabel("Demo shot")
    }
}

extension View {
    /// Plan F14: [DemoBanner] in the top safe-area inset while [on].
    func demoBanner(_ on: Bool) -> some View {
        safeAreaInset(edge: .top, spacing: 0) {
            if on {
                DemoBanner()
            }
        }
    }
}

/// Plan F14 (`--callout-probe`, debug launch hook only): the last call-out, written on screen for the
/// UI tests instead of being spoken.
@MainActor
final class CalloutProbeObserver: ObservableObject {
    @Published private(set) var lastSpoken = ""
    private var task: Task<Void, Never>?

    init() {
        guard let probe = KoinHelper().calloutProbe() else { return }
        let values = asyncSequence(for: probe.lastSpokenFlow)
        task = Task { [weak self] in
            do {
                for try await value in values {
                    self?.lastSpoken = value ?? ""
                }
            } catch {
                // A StateFlow never fails; cancellation ends the loop.
            }
        }
    }

    deinit {
        task?.cancel()
    }
}

/// Plan F14 (`--callout-probe`): the last call-out as a small caption, identified for XCUITest.
struct CalloutProbeView: View {
    @StateObject private var probe = CalloutProbeObserver()

    var body: some View {
        Text(probe.lastSpoken.isEmpty ? "No call-out yet" : probe.lastSpoken)
            .font(.of(.caption2))
            .foregroundStyle(Theme.creamDim)
            .padding(4)
            .accessibilityIdentifier("debug.calloutProbe")
            .allowsHitTesting(false)
    }
}
