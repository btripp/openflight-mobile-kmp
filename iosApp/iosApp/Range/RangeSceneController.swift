// SPDX-License-Identifier: AGPL-3.0-or-later
import QuartzCore
import RealityKit
import Shared
import UIKit

/// The RealityKit driving range, ported from the reference `RangeSceneController.swift`: ground,
/// fairway stripes, tee box, yardage targets, trees, lighting, a fixed tee-box camera, the ball
/// and its shadow, the landing marker and **one** continuous tracer.
///
/// **Camera (plan R7b).** Every frame of a flight, and for `RangeCameraRig.settleSeconds` after
/// the landing, the camera entity takes its pose and vertical field of view from the shared
/// `RangeCameraRig` for the state's camera mode: the tee camera for FIXED, the follow-ball camera
/// for FOLLOW (the same poses Android's Canvas renders). Between flights the camera holds the last
/// pose, and a mode switch re-poses it at once.
///
/// The scene layout and the flight come from the shared `core:flight` module (the reference's
/// Swift copies of it are gone): `RangeSceneDescription.standard`, `RangeCameraPlanner`,
/// `RangeTracerStyle.highVisibility`, `RangeCameraRig` and the `FlightTrajectory` in the shared ViewModel's
/// `ActiveFlight`.
///
/// **Axes.** `core:flight` uses x = lateral (right), y = up, z = downrange (+z). RealityKit's
/// camera looks down −z, and the reference's scene puts downrange at −z (the camera sits at
/// z = +8 and looks at z = −145), so flight points are mirrored in z by ``scenePosition(_:)``,
/// exactly like the reference and Android's `RangeProjection.flightToScene`. The camera pose and
/// scene description are already in scene space.
@MainActor
final class RangeSceneController: NSObject {
    private struct TracerSegmentSample {
        let startTime: TimeInterval
        let endTime: TimeInterval
        let startPosition: SIMD3<Float>
        let endPosition: SIMD3<Float>
        let width: Float
    }

    private let arView: ARView
    private let root = AnchorEntity(world: .zero)
    private let camera = PerspectiveCamera()
    private let rig = RangeCameraRig()
    private var cameraMode: RangeCameraMode = SettingsRepositoryCompanion.shared.DEFAULT_RANGE_CAMERA_MODE
    /// Playback progress of the flight on screen, 0 at launch to 1 at landing.
    private var progress: Double = 0
    /// When the ball landed (display-link time), while the camera settles; `nil` in the air.
    private var landedAt: CFTimeInterval?
    private var landedElapsed: Double = 0
    private let ball: ModelEntity
    private let ballShadow: ModelEntity
    private let landingMarker = Entity()
    private var tracerSegments: [ModelEntity] = []
    private var tracerSamples: [TracerSegmentSample?] = []
    private var tracerFullyRevealed: [Bool] = []
    private var displayLink: CADisplayLink?
    private var trajectory: FlightTrajectory?
    /// The shared `ActiveFlight.playbackId`: new for every playback, replays included.
    private var playbackID: Int64?
    private var playbackStartedAt: CFTimeInterval = 0
    private var playbackDuration: TimeInterval = 1
    private var completion: (() -> Void)?

    init(view: ARView, quality: RangeQualityProfile = .current) {
        arView = view
        let white = SimpleMaterial(color: .white, roughness: 0.28, isMetallic: false)
        ball = ModelEntity(mesh: .generateSphere(radius: 0.18), materials: [white])
        ballShadow = ModelEntity(
            mesh: .generateSphere(radius: 0.38),
            materials: [SimpleMaterial(color: UIColor.black.withAlphaComponent(0.28), isMetallic: false)]
        )
        ballShadow.scale = SIMD3(1, 0.035, 1)
        super.init()
        buildScene(quality: quality)
    }

    /// Flies `flight` unless it is the playback already running (or finished).
    func play(
        _ flight: ActiveFlight,
        reduceMotion: Bool,
        completion: @escaping () -> Void
    ) {
        guard flight.playbackId != playbackID else { return }
        stopDisplayLink()
        playbackID = flight.playbackId
        let trajectory = flight.trajectory
        self.trajectory = trajectory
        self.completion = completion
        progress = 0
        landedAt = nil
        landedElapsed = 0
        // 0.9 s under reduced motion, else clamp(flightTime * 0.68, 3.5, 6) (shared).
        playbackDuration = RangeProjectionKt.playbackSeconds(trajectory: trajectory, reduceMotion: reduceMotion)
        playbackStartedAt = CACurrentMediaTime()
        configureTracer(for: trajectory)
        landingMarker.isEnabled = false
        ball.isEnabled = true
        ballShadow.isEnabled = true
        updateScene(at: 0)
        updateCamera()

        let link = CADisplayLink(target: self, selector: #selector(displayLinkDidFire(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    /// Switches between the follow and the fixed camera; the camera re-poses at once.
    func setCameraMode(_ mode: RangeCameraMode) {
        guard mode != cameraMode else { return }
        cameraMode = mode
        updateCamera()
    }

    /// Freezes a flight in the air where it is. A landed flight's camera still finishes settling
    /// (the landing dwell outlasts the settle, so this only matters when frames fall behind).
    func suspend() {
        completion = nil
        if landedAt == nil {
            stopDisplayLink()
        }
    }

    func tearDown() {
        stopDisplayLink()
        completion = nil
        tracerSegments.removeAll()
        tracerSamples.removeAll()
        tracerFullyRevealed.removeAll()
        arView.scene.anchors.removeAll()
    }

    @objc private func displayLinkDidFire(_ link: CADisplayLink) {
        if let landedAt {
            // Landed: keep drawing until the follow camera has settled over the landing spot.
            landedElapsed = min(link.timestamp - landedAt, rig.settleSeconds)
            updateCamera()
            if landedElapsed >= rig.settleSeconds {
                stopDisplayLink()
            }
            return
        }
        let elapsed = link.timestamp - playbackStartedAt
        progress = min(max(elapsed / playbackDuration, 0), 1)
        updateScene(at: progress)
        updateCamera()
        if progress >= 1 {
            landedAt = link.timestamp
            landedElapsed = 0
            landingMarker.isEnabled = true
            let callback = completion
            completion = nil
            callback?()
        }
    }

    /// Poses the camera entity for this instant: `RangeCameraRig` gives the position, the target and
    /// the vertical field of view (RealityKit's `PerspectiveCameraComponent` default orientation).
    private func updateCamera() {
        let pose = rig.pose(
            mode: cameraMode,
            trajectory: trajectory,
            playbackProgress: progress,
            landedElapsedSeconds: landedElapsed
        )
        camera.look(at: SIMD3(pose.target), from: SIMD3(pose.position), relativeTo: nil)
        camera.camera.fieldOfViewInDegrees = Float(pose.verticalFovDegrees)
    }

    private func buildScene(quality: RangeQualityProfile) {
        arView.environment.background = .color(UIColor(red: 0.34, green: 0.63, blue: 0.88, alpha: 1))
        arView.renderOptions.insert([.disableCameraGrain, .disableDepthOfField, .disableMotionBlur])
        arView.scene.addAnchor(root)

        let description = RangeSceneDescription.companion.standard(treeCount: quality.treeCount)
        addGround(description)
        addTeeBox()
        addTargets(description.markers)
        addTrees(description.trees)
        addLighting()

        root.addChild(camera)
        updateCamera()

        ball.position = SIMD3(0, 0.18, 0)
        ballShadow.position = SIMD3(0, 0.015, 0)
        root.addChild(ballShadow)
        root.addChild(ball)

        buildLandingMarker()
        root.addChild(landingMarker)
        landingMarker.isEnabled = false

        let tracerStyle = RangeTracerStyle.companion.highVisibility
        // Flush-ended segments meet without the visible beads or gaps produced
        // by a chain of spheres. A box also keeps the iOS 17 deployment target.
        let tracerMesh = MeshResource.generateBox(size: SIMD3<Float>(1, 1, 1))
        let tracerMaterial = UnlitMaterial(
            color: UIColor(
                red: CGFloat(tracerStyle.red),
                green: CGFloat(tracerStyle.green),
                blue: CGFloat(tracerStyle.blue),
                alpha: CGFloat(tracerStyle.opacity)
            )
        )
        for _ in 0 ..< Int(quality.tracerPointCount) {
            let segment = ModelEntity(mesh: tracerMesh, materials: [tracerMaterial])
            segment.isEnabled = false
            tracerSegments.append(segment)
            root.addChild(segment)
        }
    }

    private func addGround(_ description: RangeSceneDescription) {
        let rangeDepth = Float(description.rangeDepthMeters)
        let ground = ModelEntity(
            mesh: .generateBox(size: SIMD3(180, 0.12, rangeDepth + 90)),
            materials: [
                SimpleMaterial(
                    color: UIColor(red: 0.08, green: 0.30, blue: 0.14, alpha: 1),
                    roughness: 1,
                    isMetallic: false
                )
            ]
        )
        ground.position = SIMD3(0, -0.08, -(rangeDepth - 45) / 2)
        root.addChild(ground)

        let fairwayWidth = Float(description.fairwayWidthMeters)
        let fairway = ModelEntity(
            mesh: .generateBox(size: SIMD3(fairwayWidth, 0.035, rangeDepth)),
            materials: [
                SimpleMaterial(
                    color: UIColor(red: 0.20, green: 0.52, blue: 0.22, alpha: 1),
                    roughness: 0.92,
                    isMetallic: false
                )
            ]
        )
        fairway.position = SIMD3(0, 0, -rangeDepth / 2 + 8)
        root.addChild(fairway)

        let stripeMesh = MeshResource.generateBox(size: SIMD3(fairwayWidth, 0.01, 18))
        let stripeMaterial = SimpleMaterial(
            color: UIColor(red: 0.25, green: 0.59, blue: 0.27, alpha: 1),
            roughness: 0.95,
            isMetallic: false
        )
        for index in 0 ..< 11 {
            let stripe = ModelEntity(mesh: stripeMesh, materials: [stripeMaterial])
            stripe.position = SIMD3(0, 0.025, -Float(index * 36 + 12))
            root.addChild(stripe)
        }
    }

    private func addTeeBox() {
        let tee = ModelEntity(
            mesh: .generateBox(size: SIMD3(15, 0.16, 11)),
            materials: [
                SimpleMaterial(
                    color: UIColor(red: 0.16, green: 0.47, blue: 0.20, alpha: 1),
                    roughness: 0.9,
                    isMetallic: false
                )
            ]
        )
        tee.position = SIMD3(0, 0.08, 2.5)
        root.addChild(tee)

        let teeMarkerMesh = MeshResource.generateSphere(radius: 0.33)
        let markerMaterial = SimpleMaterial(color: .white, roughness: 0.6, isMetallic: false)
        for x: Float in [-4, 4] {
            let marker = ModelEntity(mesh: teeMarkerMesh, materials: [markerMaterial])
            marker.position = SIMD3(x, 0.19, 0)
            marker.scale = SIMD3(1, 0.12, 1)
            root.addChild(marker)
        }
    }

    private func addTargets(_ markers: [RangeMarkerDescription]) {
        for (index, marker) in markers.enumerated() {
            let distance = Float(marker.yards) * 0.9144
            let radius = Float(marker.radiusMeters)
            let outer = ModelEntity(
                mesh: .generateSphere(radius: radius),
                materials: [
                    SimpleMaterial(
                        color: UIColor.white.withAlphaComponent(0.88),
                        roughness: 0.78,
                        isMetallic: false
                    )
                ]
            )
            outer.position = SIMD3(index.isMultiple(of: 2) ? -9 : 9, 0.06, -distance)
            outer.scale = SIMD3(1, 0.018, 1)
            root.addChild(outer)

            let inner = ModelEntity(
                mesh: .generateSphere(radius: radius * 0.48),
                materials: [
                    SimpleMaterial(
                        color: index.isMultiple(of: 2)
                            ? UIColor(red: 0.90, green: 0.18, blue: 0.15, alpha: 1)
                            : UIColor(red: 0.96, green: 0.72, blue: 0.08, alpha: 1),
                        roughness: 0.72,
                        isMetallic: false
                    )
                ]
            )
            inner.position = SIMD3(outer.position.x, 0.085, outer.position.z)
            inner.scale = SIMD3(1, 0.028, 1)
            root.addChild(inner)
        }
    }

    private func addTrees(_ trees: [RangeTreeDescription]) {
        let trunkMesh = MeshResource.generateBox(size: SIMD3(0.9, 4.8, 0.9), cornerRadius: 0.32)
        let crownMesh = MeshResource.generateSphere(radius: 2.8)
        let trunkMaterial = SimpleMaterial(
            color: UIColor(red: 0.29, green: 0.16, blue: 0.08, alpha: 1),
            roughness: 1,
            isMetallic: false
        )
        let crownMaterials = [
            SimpleMaterial(color: UIColor(red: 0.05, green: 0.26, blue: 0.10, alpha: 1), roughness: 1, isMetallic: false),
            SimpleMaterial(color: UIColor(red: 0.07, green: 0.34, blue: 0.13, alpha: 1), roughness: 1, isMetallic: false),
        ]

        for (index, tree) in trees.enumerated() {
            let group = Entity()
            group.position = SIMD3(Float(tree.xMeters), 0, -Float(tree.downrangeMeters))
            group.scale = SIMD3(repeating: Float(tree.scale))

            let trunk = ModelEntity(mesh: trunkMesh, materials: [trunkMaterial])
            trunk.position.y = 2.4
            group.addChild(trunk)

            let crown = ModelEntity(mesh: crownMesh, materials: [crownMaterials[index % crownMaterials.count]])
            crown.position = SIMD3(0, 6.2, 0)
            group.addChild(crown)

            let crownTop = ModelEntity(mesh: crownMesh, materials: [crownMaterials[(index + 1) % crownMaterials.count]])
            crownTop.scale = SIMD3(repeating: 0.68)
            crownTop.position = SIMD3(0.7, 8.2, -0.3)
            group.addChild(crownTop)
            root.addChild(group)
        }
    }

    private func addLighting() {
        let sun = DirectionalLight()
        sun.light.color = .white
        sun.light.intensity = 34_000
        sun.look(
            at: SIMD3<Float>(0, 0, -120),
            from: SIMD3<Float>(-75, 120, 45),
            relativeTo: nil
        )
        root.addChild(sun)
    }

    private func buildLandingMarker() {
        let outer = ModelEntity(
            mesh: .generateSphere(radius: 2.2),
            materials: [SimpleMaterial(color: UIColor.white.withAlphaComponent(0.85), isMetallic: false)]
        )
        let inner = ModelEntity(
            mesh: .generateSphere(radius: 1.35),
            materials: [
                SimpleMaterial(
                    color: UIColor(red: 1, green: 0.72, blue: 0.06, alpha: 0.95),
                    roughness: 0.35,
                    isMetallic: false
                )
            ]
        )
        outer.position.y = 0.065
        outer.scale = SIMD3(1, 0.025, 1)
        inner.position.y = 0.09
        inner.scale = SIMD3(1, 0.035, 1)
        landingMarker.addChild(outer)
        landingMarker.addChild(inner)
    }

    private func configureTracer(for trajectory: FlightTrajectory) {
        tracerSamples.removeAll(keepingCapacity: true)
        tracerFullyRevealed = Array(repeating: false, count: tracerSegments.count)
        tracerSegments.forEach { $0.isEnabled = false }
        guard !trajectory.points.isEmpty else { return }
        let tracerStyle = RangeTracerStyle.companion.highVisibility
        let nearWidth = Float(tracerStyle.nearWidthMeters)
        let farWidth = Float(tracerStyle.farWidthMeters)

        for index in tracerSegments.indices {
            let startFraction = Double(index) / Double(tracerSegments.count)
            let endFraction = Double(index + 1) / Double(tracerSegments.count)
            let startTime = trajectory.flightTime * startFraction
            let endTime = trajectory.flightTime * endFraction
            guard let startPoint = trajectory.point(at: startTime),
                  let endPoint = trajectory.point(at: endTime)
            else {
                tracerSamples.append(nil)
                continue
            }

            let widthFraction = Float((startFraction + endFraction) / 2)
            let width = nearWidth + widthFraction * (farWidth - nearWidth)
            let sample = TracerSegmentSample(
                startTime: startTime,
                endTime: endTime,
                startPosition: scenePosition(startPoint.positionMeters),
                endPosition: scenePosition(endPoint.positionMeters),
                width: width
            )
            tracerSamples.append(sample)
            configureTracerSegment(tracerSegments[index], sample: sample)
        }
    }

    private func updateScene(at progress: Double) {
        guard let trajectory,
              let point = trajectory.point(at: trajectory.flightTime * progress)
        else {
            return
        }
        let position = scenePosition(point.positionMeters)
        ball.position = position
        ballShadow.position = SIMD3(position.x, 0.025, position.z)
        let shadowScale = max(0.45, 1 - position.y / 85)
        ballShadow.scale = SIMD3(shadowScale, 0.035, shadowScale)

        let currentTime = trajectory.flightTime * progress
        for (index, segment) in tracerSegments.enumerated() {
            guard index < tracerSamples.count, let sample = tracerSamples[index] else {
                segment.isEnabled = false
                continue
            }

            if currentTime <= sample.startTime {
                segment.isEnabled = false
            } else if currentTime >= sample.endTime {
                if !tracerFullyRevealed[index] {
                    configureTracerSegment(segment, sample: sample)
                    tracerFullyRevealed[index] = true
                }
                segment.isEnabled = true
            } else {
                let segmentProgress = Float(
                    (currentTime - sample.startTime) / (sample.endTime - sample.startTime)
                )
                let partialEnd = sample.startPosition
                    + (sample.endPosition - sample.startPosition) * segmentProgress
                configureTracerSegment(
                    segment,
                    from: sample.startPosition,
                    to: partialEnd,
                    width: sample.width
                )
                segment.isEnabled = true
            }
        }

        if progress >= 1 {
            landingMarker.position = SIMD3(position.x, 0, position.z)
        }
    }

    /// Simulator space (+z downrange) to scene space (−z downrange), lifted by the ball radius.
    private func scenePosition(_ position: Vec3) -> SIMD3<Float> {
        SIMD3(Float(position.x), Float(position.y) + 0.18, -Float(position.z))
    }

    private func configureTracerSegment(
        _ segment: ModelEntity,
        sample: TracerSegmentSample
    ) {
        configureTracerSegment(
            segment,
            from: sample.startPosition,
            to: sample.endPosition,
            width: sample.width
        )
    }

    private func configureTracerSegment(
        _ segment: ModelEntity,
        from start: SIMD3<Float>,
        to end: SIMD3<Float>,
        width: Float
    ) {
        let delta = end - start
        let length = simd_length(delta)
        guard length > 0.0001 else {
            segment.isEnabled = false
            return
        }

        segment.position = (start + end) / 2
        segment.orientation = simd_quatf(
            from: SIMD3<Float>(0, 1, 0),
            to: delta / length
        )
        segment.scale = SIMD3(width, length, width)
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }
}

private extension SIMD3 where Scalar == Float {
    /// A camera-pose point from `core:flight` (already in scene space).
    init(_ vector: Vec3) {
        self.init(Float(vector.x), Float(vector.y), Float(vector.z))
    }
}

extension RangeQualityProfile {
    /// The reference's `RangeQualityProfile.current`: fewer trees and tracer segments on devices
    /// with less than ~4.5 GB of memory. (`core:flight` leaves this device check to the platform.)
    static var current: RangeQualityProfile {
        ProcessInfo.processInfo.physicalMemory < 4_500_000_000 ? .balanced : .high
    }
}
