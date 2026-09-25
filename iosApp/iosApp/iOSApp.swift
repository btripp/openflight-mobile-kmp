// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    /// Foreground/background for the shared connection policy (plan R8d): it starts and stops the
    /// shot stream (or the `--ui-testing` preview repository) and the Pi session.
    private let lifecycle: AppLifecycle
    private let launchOptions: LaunchOptions

    init() {
        // The shared Koin graph (core:data + the feature ViewModels), started once per process.
        // Debug builds also apply the launch arguments here (`LaunchOptions`): `--ui-testing` and
        // `--preview-shot` swap in a transport-free repository, so no connection ever starts.
        KoinHelperKt.startKoinForIos()
        let koin = KoinHelper()
        lifecycle = koin.appLifecycle()
        launchOptions = koin.launchOptions()
        AppearanceSetup.apply()
    }

    var body: some Scene {
        WindowGroup {
            AppRoot(launchOptions: launchOptions)
        }
        // Streaming is foreground-only, like the reference and Android's ProcessLifecycleOwner: it
        // runs for every screen while the app is active and disconnects in the background.
        // `.inactive` (Control Center, the app switcher) keeps the connection.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active: lifecycle.onForeground()
            case .background: lifecycle.onBackground()
            default: break
            }
        }
    }
}
