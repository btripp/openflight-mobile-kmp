// SPDX-License-Identifier: AGPL-3.0-or-later
import os
import Shared
import SwiftUI
import UIKit

/// Plan F8b: the range's view gestures, the iOS twin of Android's `rangeViewGestures`. A pinch
/// zooms, a two-finger drag pans along the ground (the ground point under the fingers stays under
/// them, through the shared `ViewTransform.pannedAlongGround`), a one-finger drag orbits, a double
/// tap resets, and in the overlay a tap near a landing selects that shot (the shared
/// `nearestOverlayLanding`). Each gesture reports the whole new `ViewTransform`, already clamped by
/// the shared math.
///
/// UIKit recognizers, because SwiftUI has no two-finger drag and its magnify and drag gestures
/// can't be told apart by finger count. The layer sits over the Canvas and is invisible to
/// VoiceOver (the scene's accessibility element describes the view instead).
///
/// **Units.** The shared projection works in device pixels (points × `displayScale`, plan F8c2),
/// so every touch location is scaled up before it reaches the shared math.
struct RangeGestureLayer: UIViewRepresentable {
    /// The camera the scene was last drawn with (the shared `RangeFrame.projection`).
    let projection: () -> RangeProjection?
    let view: ViewTransform
    let overlay: [OverlayFlight]
    let displayScale: CGFloat
    let onViewChanged: (ViewTransform) -> Void
    let onResetView: () -> Void
    let onSelectLanding: (String) -> Void

    /// How far from a landing dot (points) a tap still selects it, Android's `TAP_REACH_DP`.
    static let tapReachPoints: CGFloat = 40

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIView {
        let surface = UIView()
        surface.backgroundColor = .clear
        surface.isMultipleTouchEnabled = true
        surface.isAccessibilityElement = false
        surface.accessibilityElementsHidden = true
        let coordinator = context.coordinator

        let pinch = UIPinchGestureRecognizer(target: coordinator, action: #selector(Coordinator.pinched(_:)))
        let pan = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.panned(_:)))
        pan.minimumNumberOfTouches = 2
        pan.maximumNumberOfTouches = 2
        let orbit = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.orbited(_:)))
        orbit.maximumNumberOfTouches = 1
        let doubleTap = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        let tap = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.require(toFail: doubleTap)

        for recognizer in [pinch, pan, orbit, doubleTap, tap] as [UIGestureRecognizer] {
            recognizer.delegate = coordinator
            surface.addGestureRecognizer(recognizer)
        }
        coordinator.pinch = pinch
        coordinator.pan = pan
        return surface
    }

    func updateUIView(_: UIView, context: Context) {
        context.coordinator.parent = self
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: RangeGestureLayer
        weak var pinch: UIPinchGestureRecognizer?
        weak var pan: UIPanGestureRecognizer?

        /// The transform the running gesture has built so far. Accumulated here, like Android's
        /// `local`: the view model's state can lag a touch event behind.
        private var local: ViewTransform?
        private var active = Set<ObjectIdentifier>()
        private var lastCentroid: CGPoint?
        #if DEBUG
        /// The gesture's transform updates and when it began, for the `RangeCanvas` log: compared
        /// with the canvas's "range redraws" line it shows whether redraws keep up with the touches.
        private var updates = 0
        private var began: CFTimeInterval = 0
        private let logger = Logger(subsystem: "dev.openflight.companion", category: "RangeCanvas")
        #endif

        init(_ parent: RangeGestureLayer) {
            self.parent = parent
        }

        // MARK: Gestures

        @objc func pinched(_ recognizer: UIPinchGestureRecognizer) {
            track(recognizer) { current in
                let factor = Double(recognizer.scale)
                recognizer.scale = 1
                return current.zoomedBy(factor: factor)
            }
        }

        @objc func panned(_ recognizer: UIPanGestureRecognizer) {
            if recognizer.state == .began { lastCentroid = recognizer.location(in: recognizer.view) }
            track(recognizer) { current in
                let centroid = recognizer.location(in: recognizer.view)
                defer { lastCentroid = centroid }
                // A finger lifted and landed again moves the centroid in a jump; skip that step.
                guard recognizer.numberOfTouches == 2,
                      let from = lastCentroid,
                      let projection = parent.projection()
                else { return current }
                let scale = Float(max(parent.displayScale, 1))
                return current.pannedAlongGround(
                    projection: projection,
                    fromX: Float(from.x) * scale,
                    fromY: Float(from.y) * scale,
                    toX: Float(centroid.x) * scale,
                    toY: Float(centroid.y) * scale,
                    bounds: PanBounds.companion.STANDARD
                )
            }
            if recognizer.state != .changed && recognizer.state != .began { lastCentroid = nil }
        }

        @objc func orbited(_ recognizer: UIPanGestureRecognizer) {
            track(recognizer) { current in
                let width = recognizer.view?.bounds.width ?? 0
                let dx = recognizer.translation(in: recognizer.view).x
                recognizer.setTranslation(.zero, in: recognizer.view)
                guard width > 0 else { return current }
                return current.orbitedBy(
                    deltaDegrees: Double(dx / width) * ViewTransform.companion.ORBIT_DEGREES_PER_WIDTH
                )
            }
        }

        @objc func doubleTapped(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended else { return }
            parent.onResetView()
        }

        @objc func tapped(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended,
                  !parent.overlay.isEmpty,
                  let projection = parent.projection()
            else { return }
            let point = recognizer.location(in: recognizer.view)
            let scale = max(parent.displayScale, 1)
            let shotId = RangeFlightsKt.nearestOverlayLanding(
                projection: projection,
                flights: parent.overlay,
                x: Float(point.x * scale),
                y: Float(point.y * scale),
                maxDistancePixels: Float(RangeGestureLayer.tapReachPoints * scale)
            )
            if let shotId { parent.onSelectLanding(shotId) }
        }

        /// Runs one step of a continuous gesture on the shared `local` transform and reports it
        /// when it changed. Pinch and pan run together, so `local` lives until both have ended.
        private func track(_ recognizer: UIGestureRecognizer, step: (ViewTransform) -> ViewTransform) {
            let id = ObjectIdentifier(recognizer)
            switch recognizer.state {
            case .began:
                if active.isEmpty {
                    local = parent.view
                    #if DEBUG
                    updates = 0
                    began = CACurrentMediaTime()
                    #endif
                }
                active.insert(id)
            case .changed:
                guard let current = local else { return }
                let next = step(current)
                if !next.isEqual(current) {
                    local = next
                    parent.onViewChanged(next)
                    #if DEBUG
                    updates += 1
                    #endif
                }
            default:
                active.remove(id)
                if active.isEmpty, local != nil {
                    local = nil
                    #if DEBUG
                    let seconds = CACurrentMediaTime() - began
                    logger.debug(
                        "range gesture \(self.updates) view updates in \(seconds, format: .fixed(precision: 2)) s: \(Double(self.updates) / max(seconds, 0.001), format: .fixed(precision: 1)) per s"
                    )
                    #endif
                }
            }
        }

        // MARK: UIGestureRecognizerDelegate

        /// Pinch and the two-finger pan run together (zoom while sliding); the orbit and the taps
        /// stay exclusive.
        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer
        ) -> Bool {
            let twoFinger: (UIGestureRecognizer) -> Bool = {
                $0 is UIPinchGestureRecognizer || (($0 as? UIPanGestureRecognizer)?.minimumNumberOfTouches == 2)
            }
            return twoFinger(gestureRecognizer) && twoFinger(other)
        }
    }
}
