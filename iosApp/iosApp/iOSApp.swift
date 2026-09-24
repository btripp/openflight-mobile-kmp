// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    /// The app-wide shot stream (or the `--ui-testing` preview repository).
    private let shots: ShotRepository
    private let launchOptions: LaunchOptions

    init() {
        // The shared Koin graph (core:data + the feature ViewModels), started once per process.
        // Debug builds also apply the launch arguments here (`LaunchOptions`): `--ui-testing` and
        // `--preview-shot` swap in a transport-free repository, so no connection ever starts.
        KoinHelperKt.startKoinForIos()
        let koin = KoinHelper()
        shots = koin.shotRepository()
        launchOptions = koin.launchOptions()
    }

    var body: some Scene {
        WindowGroup {
            AppRoot(launchOptions: launchOptions)
        }
        // Streaming is foreground-only, like the reference and Android's LifecycleStartEffect: it
        // runs for every screen while the app is active and disconnects in the background.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active: shots.start()
            case .background: shots.stop()
            default: break
            }
        }
    }
}
