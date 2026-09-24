import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        // The shared Koin graph (core:data + the feature ViewModels), started once per process.
        KoinHelperKt.startKoinForIos()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
