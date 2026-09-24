// SPDX-License-Identifier: AGPL-3.0-or-later
import RealityKit
import Shared
import SwiftUI

/// Hosts the RealityKit range in a non-AR `ARView` (reference `RangeSceneView.swift`).
///
/// `flight` is the shared `DrivingRangeUiState.activeFlight`: a new `playbackId` starts a new
/// playback (a replay of the same shot included), `nil` suspends the animation. The scene reports
/// the end of a flight through `onFlightCompleted`, which the screen forwards to the ViewModel as
/// `FlightCompleted`.
struct RangeSceneView: UIViewRepresentable {
    let flight: ActiveFlight?
    let reduceMotion: Bool
    let onFlightCompleted: @MainActor () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.accessibilityIdentifier = RangeTestTags.shared.SCENE
        context.coordinator.controller = RangeSceneController(view: view)
        return view
    }

    func updateUIView(_ uiView: ARView, context: Context) {
        context.coordinator.onFlightCompleted = onFlightCompleted
        guard let flight else {
            context.coordinator.controller?.suspend()
            return
        }
        context.coordinator.controller?.play(
            flight,
            reduceMotion: reduceMotion,
            completion: { [weak coordinator = context.coordinator] in
                coordinator?.onFlightCompleted?()
            }
        )
    }

    static func dismantleUIView(_ uiView: ARView, coordinator: Coordinator) {
        coordinator.controller?.tearDown()
        coordinator.controller = nil
    }

    @MainActor
    final class Coordinator {
        var controller: RangeSceneController?
        var onFlightCompleted: (@MainActor () -> Void)?
    }
}
