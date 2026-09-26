// SPDX-License-Identifier: AGPL-3.0-or-later
import os
import Shared
import SwiftUI

/// The driving range drawn with SwiftUI `Canvas` from the shared Kotlin geometry (plan F8c2,
/// ADR 0002), the iOS twin of Android's `RangeCanvas`. The shared `RangeFrame` keeps the scene, the
/// flight, the landing marker, the tracer, the F8a1 overlay and the roll-out projected; this view
/// only paints them, in the frame's documented order, with the colours and sizes of
/// `RangeVisualStyle`. The per-point work stays in Kotlin: every outline is written into a
/// `CGPathSink`, and Swift fills each finished path once.
///
/// **Playback (mirrors `RangeCanvas.kt`).** A new `ActiveFlight.playbackId` starts a playback that
/// runs for `playbackSeconds` (divided by the replay speed) and then calls `onFlightCompleted`. The
/// clock then keeps running for `RangeCameraRig.settleSeconds` so the follow camera settles over the
/// landing. A `nil` flight freezes the last one where it is (suspend); only one tracer is ever
/// drawn. The `TimelineView` runs only while a flight or a settle is in progress; otherwise the
/// canvas redraws only when its inputs change.
///
/// **Camera.** Each frame takes its pose from the shared `RangeCameraRig` for `cameraMode`, or,
/// while the user's `ViewTransform` isn't the identity (plan F8a), the fixed tee camera with the
/// transform applied (the follow camera is suspended, as on Android).
///
/// **Units.** The frame is projected in device pixels (points × `displayScale`), like Android's
/// Canvas, so the shared pixel minimums (tracer width, ball radius) and the style's raw-pixel dash
/// match Android at the same density. Style sizes in points are scaled up to pixels; labels are
/// drawn in points.
///
/// **Gestures (plan F8b).** With `onViewChanged` set, `RangeGestureLayer` sits over the canvas: pinch,
/// two-finger pan, one-finger orbit, double-tap reset and, in the overlay, tap-to-select a landing,
/// all through the shared `ViewTransform` math and this frame's projection.
struct RangeCanvasView: View {
    let flight: ActiveFlight?
    let cameraMode: RangeCameraMode
    let reduceMotion: Bool
    var view: ViewTransform = ViewTransform.companion.IDENTITY
    var rollOut: RangeRollOut?
    var overlay: [OverlayFlight] = []
    var overlayMode = false
    var selectedOverlayId: String?
    /// Debug (`--range-freeze-progress`): hold every flight at this playback progress.
    var freezeProgress: Double?
    let onFlightCompleted: @MainActor () -> Void
    /// Plan F8b: a gesture's new transform; `nil` leaves the scene without gestures.
    var onViewChanged: ((ViewTransform) -> Void)?
    var onResetView: () -> Void = {}
    var onSelectLanding: (String) -> Void = { _ in }

    @StateObject private var player = RangeCanvasPlayer()
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: !player.animating)) { timeline in
            Canvas(opaque: true) { context, size in
                player.render(
                    in: context,
                    size: size,
                    now: timeline.date,
                    displayScale: displayScale,
                    inputs: RangeCanvasPlayer.Inputs(
                        cameraMode: cameraMode,
                        view: view,
                        rollOut: rollOut,
                        overlay: overlay,
                        selectedOverlayId: selectedOverlayId
                    )
                )
            }
        }
        .overlay {
            if let onViewChanged {
                RangeGestureLayer(
                    projection: { [player] in player.frame.projection },
                    view: view,
                    overlay: overlayMode ? overlay : [],
                    displayScale: displayScale,
                    onViewChanged: onViewChanged,
                    onResetView: onResetView,
                    onSelectLanding: onSelectLanding
                )
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Driving range")
        .accessibilityValue(viewDescription)
        .accessibilityIdentifier(RangeTestTags.shared.SCENE)
        .overlay {
            if overlayMode, onViewChanged != nil {
                RangeLandingMarkers(
                    markers: player.landingMarkers,
                    selectedId: selectedOverlayId,
                    onSelect: onSelectLanding
                )
            }
        }
        .onAppear(perform: syncFlight)
        .onChange(of: flight?.playbackId) { syncFlight() }
        .onChange(of: overlayMode) {
            // Entering the overlay drops the frozen tracer: the overlay's own trajectories replace it.
            if overlayMode, flight == nil { player.dropShownFlight() }
        }
    }

    private func syncFlight() {
        player.sync(
            flight: flight,
            reduceMotion: reduceMotion,
            freezeProgress: freezeProgress,
            onCompleted: onFlightCompleted
        )
    }

    /// Android's scene state description: "Default view", or the zoom and orbit the user chose.
    private var viewDescription: String {
        view.isIdentity
            ? "Default view"
            : "Zoom \(Int((view.zoom * 100).rounded())) percent, orbit \(Int(view.orbitYawDegrees.rounded())) degrees"
    }
}

/// The range's playback clock and its shared `RangeFrame`: one per range screen. Only `animating`
/// is published (it starts and stops the `TimelineView`); everything else changes inside `render`,
/// which must not publish, so the completion and the stop are posted to the next main-loop turn.
@MainActor
final class RangeCanvasPlayer: ObservableObject {
    struct Inputs {
        let cameraMode: RangeCameraMode
        let view: ViewTransform
        let rollOut: RangeRollOut?
        let overlay: [OverlayFlight]
        let selectedOverlayId: String?
    }

    private enum Clock {
        case idle
        /// Flying for `duration` seconds from `start` (the first rendered frame's time).
        case flying(start: Date?, duration: Double)
        /// Landed `landedAt`, settling the follow camera.
        case settling(landedAt: Date?)
    }

    @Published private(set) var animating = false

    /// Plan F8b: where the overlay's landing dots were last drawn (points), for the accessible
    /// landing markers. Published once the redraws settle, not per frame.
    @Published private(set) var landingMarkers: [RangeLandingMarker] = []
    private var markersScheduled = false
    private var markersScale: CGFloat = 1

    let rig = RangeCameraRig()
    let style = RangeTheme.day.style
    let frame: RangeFrame<CGPathSink>

    private var shown: ActiveFlight?
    private var playingId: Int64?
    private var progress = 0.0
    private var landedSeconds = 0.0
    private var clock = Clock.idle
    private var completion: (@MainActor () -> Void)?

    // What the frame was last given, so it's only told about changes.
    private var canvasSize = CGSize.zero
    private var frameFlight: ActiveFlight?
    private var frameOverlay: [OverlayFlight] = []
    private var frameSelectedId: String?
    private var frameRollOut: RangeRollOut?
    private var frameRollOutTrajectory: FlightTrajectory?

    // The static scene, read once from the frame, with its colours resolved once.
    private let polygons: [WorldPolygon<CGPathSink>]
    private let polygonColors: [Color]
    private let trees: [WorldTree<CGPathSink>]
    private let treeColors: [(trunk: Color, crown: Color, crownTop: Color)]
    private let labels: [WorldLabel]
    private let skyGradient: Gradient
    private let colors: Palette

    private let frameCounter = RangeFrameCounter()

    private struct Palette {
        let ground, tracer, ball, shadow, label, selected, rollOut, landingOuter, landingInner: Color
    }

    init() {
        let style = RangeTheme.day.style
        let frame = RangeFrame<CGPathSink>(
            style: style,
            clubPaletteSize: Int32(Theme.clubColors.count),
            newPath: { CGPathSink() },
            description: RangeSceneDescription.companion.standard(treeCount: RangeFrameCompanion.shared.QUALITY.treeCount)
        )
        self.frame = frame
        polygons = frame.scene.polygons
        polygonColors = polygons.map { $0.color.swiftUI }
        trees = frame.scene.trees
        treeColors = trees.map { ($0.trunk.color.swiftUI, $0.crown.color.swiftUI, $0.crownTop.color.swiftUI) }
        labels = frame.scene.labels
        skyGradient = Gradient(colors: [style.skyTop.swiftUI, style.skyHorizon.swiftUI])
        colors = Palette(
            ground: style.ground.swiftUI,
            tracer: style.tracer.swiftUI,
            ball: style.ball.swiftUI,
            shadow: style.shadow.swiftUI,
            label: style.label.swiftUI,
            selected: style.overlaySelected.swiftUI,
            rollOut: style.rollOut.swiftUI,
            landingOuter: style.landingOuter.swiftUI,
            landingInner: style.landingInner.swiftUI
        )
    }

    // MARK: Playback

    /// Starts a new playback, or freezes the shown flight when `flight` goes away (suspend, or the
    /// landing dwell ending: a landed flight keeps settling).
    func sync(
        flight: ActiveFlight?,
        reduceMotion: Bool,
        freezeProgress: Double?,
        onCompleted: @escaping @MainActor () -> Void
    ) {
        guard let flight else {
            playingId = nil
            completion = nil
            if case .flying = clock {
                clock = .idle
                setAnimating(false)
            }
            return
        }
        guard flight.playbackId != playingId else { return }
        playingId = flight.playbackId
        shown = flight
        progress = 0
        landedSeconds = 0
        completion = onCompleted
        frameCounter.reset()
        if let freezeProgress {
            // Debug: hold the flight (or, at 1, its fully settled landing) for screenshots.
            progress = freezeProgress
            clock = .idle
            if freezeProgress >= 1 {
                landedSeconds = rig.settleSeconds
                finishFlight()
            }
            setAnimating(false)
            return
        }
        let seconds = RangeProjectionKt.playbackSeconds(trajectory: flight.trajectory, reduceMotion: reduceMotion)
        clock = .flying(start: nil, duration: seconds / max(flight.speed, 0.1))
        setAnimating(true)
    }

    func dropShownFlight() {
        shown = nil
        clock = .idle
        setAnimating(false)
    }

    /// Advances the clock to `now` (a rendered frame's time).
    private func advance(to now: Date) {
        switch clock {
        case .idle:
            return
        case let .flying(start, duration):
            guard let start else {
                // Plan F8b fix: the first render of a new flight usually comes from the input
                // change while the `TimelineView` is still paused, whose date is the last frame's,
                // possibly seconds old; starting from it made the flight "land" at once. Start at
                // the later of the two instead.
                clock = .flying(start: max(now, Date()), duration: duration)
                return
            }
            progress = min(max(now.timeIntervalSince(start) / duration, 0), 1)
            if progress >= 1 {
                clock = .settling(landedAt: now)
                finishFlight()
            }
        case let .settling(landedAt):
            guard let landedAt else {
                clock = .settling(landedAt: now)
                return
            }
            landedSeconds = min(now.timeIntervalSince(landedAt), rig.settleSeconds)
            if landedSeconds >= rig.settleSeconds {
                clock = .idle
                setAnimating(false)
            }
        }
    }

    private func finishFlight() {
        let callback = completion
        completion = nil
        if let callback {
            DispatchQueue.main.async { callback() }
        }
    }

    /// Posted: `render` runs during a view update, which must not publish.
    private func setAnimating(_ value: Bool) {
        if !value { frameCounter.finish() }
        DispatchQueue.main.async { [weak self] in
            guard let self, self.animating != value else { return }
            self.animating = value
        }
    }

    // MARK: Drawing

    func render(in context: GraphicsContext, size: CGSize, now: Date, displayScale: CGFloat, inputs: Inputs) {
        let renderStart = CACurrentMediaTime()
        defer { frameCounter.rendered(start: renderStart, overlayCount: inputs.overlay.count) }
        advance(to: now)
        if case .idle = clock {} else { frameCounter.tick(now) }
        guard size.width > 0, size.height > 0 else { return }
        let scale = max(displayScale, 1)
        if size != canvasSize {
            canvasSize = size
            frame.resize(width: Float(size.width * scale), height: Float(size.height * scale), pose: rig.fixedPose)
        }
        updateFrameInputs(inputs)

        let pose = inputs.view.isIdentity
            ? rig.pose(
                mode: inputs.cameraMode,
                trajectory: shown?.trajectory,
                playbackProgress: progress,
                landedElapsedSeconds: landedSeconds
            )
            // Plan F8a: the follow camera is suspended while the user has moved the view.
            : inputs.view.applyTo(base: rig.fixedPose)
        guard frame.prepare(pose: pose, progress: Float(progress)) else { return }

        // Everything but the labels is drawn in device pixels, like the shared geometry.
        var pixels = context
        pixels.scaleBy(x: 1 / scale, y: 1 / scale)
        let width = size.width * scale
        let height = size.height * scale

        drawBackdrop(pixels, width: width, height: height)
        drawPolygons(pixels, polygons, colors: polygonColors)
        drawTrees(pixels)
        drawLabels(context, scale: scale)
        let overlay = frame.overlay
        if let overlay { drawOverlay(pixels, overlay, scale: scale) }
        if overlay != nil || !landingMarkers.isEmpty { scheduleLandingMarkers(scale: scale) }
        if frame.geometry == nil {
            if overlay != nil { drawRollOut(pixels, context, rollOut: inputs.rollOut, scale: scale) }
        } else {
            if progress >= 1 {
                drawLanding(pixels)
                drawRollOut(pixels, context, rollOut: inputs.rollOut, scale: scale)
            }
            drawFlight(pixels)
        }
    }

    /// Publishes the overlay's landing points once the redraws pause (posted: `render` must not
    /// publish, and a gesture redraws every frame).
    private func scheduleLandingMarkers(scale: CGFloat) {
        markersScale = scale
        guard !markersScheduled else { return }
        markersScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self else { return }
            self.markersScheduled = false
            let markers = self.currentLandingMarkers()
            if markers != self.landingMarkers { self.landingMarkers = markers }
        }
    }

    private func currentLandingMarkers() -> [RangeLandingMarker] {
        guard let overlay = frame.overlay else { return [] }
        let flights = overlay.flights
        return flights.enumerated().compactMap { index, flight in
            let x = overlay.landingXs.get(index: Int32(index))
            let y = overlay.landingYs.get(index: Int32(index))
            guard !x.isNaN, !y.isNaN else { return nil }
            return RangeLandingMarker(
                shotId: flight.shotId,
                label: "Landing \(index + 1) of \(flights.count), \(Units.clubLabel(flight.club))",
                point: CGPoint(x: CGFloat(x) / markersScale, y: CGFloat(y) / markersScale)
            )
        }
    }

    /// Tells the frame what changed since the last render (it compares by identity, and a bridged
    /// Swift array would be a new list every time).
    private func updateFrameInputs(_ inputs: Inputs) {
        if shown !== frameFlight {
            frameFlight = shown
            frame.setFlight(flight: shown, segments: RangeFrameCompanion.shared.QUALITY.tracerPointCount)
        }
        let overlayChanged = inputs.overlay.count != frameOverlay.count
            || zip(inputs.overlay, frameOverlay).contains { $0 !== $1 }
        if overlayChanged || inputs.selectedOverlayId != frameSelectedId {
            if overlayChanged { frameOverlay = inputs.overlay }
            frameSelectedId = inputs.selectedOverlayId
            frame.setOverlay(flights: frameOverlay, selectedId: frameSelectedId)
        }
        let trajectory = shown?.trajectory
            ?? frameOverlay.first { $0.shotId == inputs.selectedOverlayId }?.trajectory
        if inputs.rollOut !== frameRollOut || trajectory !== frameRollOutTrajectory {
            frameRollOut = inputs.rollOut
            frameRollOutTrajectory = trajectory
            frame.setRollOut(rollOut: inputs.rollOut, trajectory: trajectory)
        }
    }

    /// Sky down to the horizon, distant ground below it (the ground plane is finite).
    private func drawBackdrop(_ context: GraphicsContext, width: CGFloat, height: CGFloat) {
        let horizon = CGFloat(frame.scene.backdropHorizon(height: Float(height)))
        if horizon < height {
            context.fill(
                Path(CGRect(x: 0, y: horizon, width: width, height: height - horizon)),
                with: .color(colors.ground)
            )
        }
        if horizon > 0 {
            context.fill(
                Path(CGRect(x: 0, y: 0, width: width, height: horizon)),
                with: .linearGradient(skyGradient, startPoint: .zero, endPoint: CGPoint(x: 0, y: horizon))
            )
        }
    }

    private func drawPolygons(_ context: GraphicsContext, _ polygons: [WorldPolygon<CGPathSink>], colors: [Color]) {
        for (index, polygon) in polygons.enumerated() where polygon.visible {
            if let path = polygon.path.cgPath { context.fill(Path(path), with: .color(colors[index])) }
        }
    }

    private func drawLanding(_ context: GraphicsContext) {
        let landing = frame.landing
        for (index, polygon) in landing.enumerated() where polygon.visible {
            if let path = polygon.path.cgPath {
                context.fill(Path(path), with: .color(index == 0 ? colors.landingOuter : colors.landingInner))
            }
        }
    }

    private func drawTrees(_ context: GraphicsContext) {
        let order = frame.scene.treeOrder
        for position in 0 ..< Int(order.size) {
            let index = Int(order.get(index: Int32(position)))
            let tree = trees[index]
            let color = treeColors[index]
            if tree.trunk.visible, let path = tree.trunk.path.cgPath {
                context.fill(Path(path), with: .color(color.trunk))
            }
            drawSphere(context, tree.crown, color.crown)
            drawSphere(context, tree.crownTop, color.crownTop)
        }
    }

    private func drawSphere(_ context: GraphicsContext, _ sphere: WorldSphere, _ color: Color) {
        guard sphere.visible else { return }
        context.fill(
            circle(x: sphere.screenX, y: sphere.screenY, radius: CGFloat(sphere.screenRadius)),
            with: .color(color)
        )
    }

    /// Yardage labels, laid out at the style's largest size, bottom-centred on their anchor and
    /// scaled about it to the size the marker's depth gives. Drawn in points.
    private func drawLabels(_ context: GraphicsContext, scale: CGFloat) {
        let maxPoints = CGFloat(style.maxLabelSize)
        for label in labels where label.visible {
            let fontPixels = label.fontPixels(
                heightMeters: style.labelHeightMeters,
                minPixels: style.minLabelSize * Float(scale),
                maxPixels: style.maxLabelSize * Float(scale)
            )
            let factor = CGFloat(fontPixels) / (maxPoints * scale)
            var labelContext = context
            labelContext.translateBy(x: CGFloat(label.anchorX) / scale, y: CGFloat(label.anchorY) / scale)
            labelContext.scaleBy(x: factor, y: factor)
            let text = labelContext.resolve(
                Text(label.text)
                    .font(.system(size: maxPoints, weight: .bold))
                    .foregroundColor(colors.label)
            )
            labelContext.draw(text, at: .zero, anchor: .bottom)
        }
    }

    /// Plan F8a1: the club-coloured static trajectories, their landing dots, then the selection.
    private func drawOverlay(_ context: GraphicsContext, _ overlay: OverlayGeometry<CGPathSink>, scale: CGFloat) {
        let stroke = StrokeStyle(lineWidth: CGFloat(style.overlayStrokeWidth) * scale, lineCap: .round, lineJoin: .round)
        let alpha = Double(style.overlayTracerAlpha)
        let groups = overlay.groupPaths
        for (index, sink) in groups.enumerated() {
            guard let path = sink.cgPath else { continue }
            let colorIndex = Int(overlay.groupColorIndices.get(index: Int32(index)))
            context.stroke(Path(path), with: .color(Theme.clubColor(colorIndex).opacity(alpha)), style: stroke)
        }
        let dotRadius = CGFloat(style.overlayDotRadius) * scale
        for index in 0 ..< overlay.flights.count {
            let x = overlay.landingXs.get(index: Int32(index))
            if x.isNaN { continue }
            let colorIndex = Int(overlay.landingColorIndices.get(index: Int32(index)))
            context.fill(
                circle(x: x, y: overlay.landingYs.get(index: Int32(index)), radius: dotRadius),
                with: .color(Theme.clubColor(colorIndex))
            )
        }
        let selected = Int(overlay.selectedIndex)
        guard selected >= 0 else { return }
        if let path = overlay.selectedPath.cgPath {
            context.stroke(
                Path(path),
                with: .color(colors.selected),
                style: StrokeStyle(lineWidth: CGFloat(style.selectedStrokeWidth) * scale, lineCap: .round, lineJoin: .round)
            )
        }
        let x = overlay.landingXs.get(index: Int32(selected))
        if !x.isNaN {
            context.fill(
                circle(x: x, y: overlay.landingYs.get(index: Int32(selected)), radius: dotRadius * 2),
                with: .color(colors.selected)
            )
        }
    }

    /// The carry → total segment, the total dot and its "est." label (plan F2/F8a1).
    private func drawRollOut(
        _ context: GraphicsContext,
        _ points: GraphicsContext,
        rollOut: RangeRollOut?,
        scale: CGFloat
    ) {
        guard frame.rollOutVisible else { return }
        let start = CGPoint(x: CGFloat(frame.rollOutStartX), y: CGFloat(frame.rollOutStartY))
        let end = CGPoint(x: CGFloat(frame.rollOutEndX), y: CGFloat(frame.rollOutEndY))
        var line = Path()
        line.move(to: start)
        line.addLine(to: end)
        context.stroke(
            line,
            with: .color(colors.rollOut),
            style: StrokeStyle(
                lineWidth: CGFloat(style.rollOutStrokeWidth) * scale,
                dash: [CGFloat(style.rollOutDashPixels), CGFloat(style.rollOutGapPixels)]
            )
        )
        let dotRadius = CGFloat(style.rollOutDotRadius) * scale
        context.fill(circle(x: frame.rollOutEndX, y: frame.rollOutEndY, radius: dotRadius), with: .color(colors.rollOut))
        guard let rollOut else { return }
        let text = points.resolve(
            Text(rollOut.totalLabel)
                .font(.system(size: CGFloat(style.rollOutLabelSize), weight: .semibold))
                .foregroundColor(colors.label)
        )
        points.draw(
            text,
            at: CGPoint(x: end.x / scale, y: (end.y - dotRadius * 2) / scale),
            anchor: .bottom
        )
    }

    /// The ball's shadow, the tracer up to the frame's position and the ball on its tip.
    private func drawFlight(_ context: GraphicsContext) {
        if frame.shadowVisible {
            let radiusX = CGFloat(frame.shadowRadiusX)
            let radiusY = CGFloat(frame.shadowRadiusY)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: CGFloat(frame.shadowX) - radiusX,
                    y: CGFloat(frame.shadowY) - radiusY,
                    width: radiusX * 2,
                    height: radiusY * 2
                )),
                with: .color(colors.shadow)
            )
        }
        let tracer = frame.tracer
        if let path = tracer.path.cgPath { context.fill(Path(path), with: .color(colors.tracer)) }
        if !tracer.tipX.isNaN {
            context.fill(circle(x: tracer.tipX, y: tracer.tipY, radius: CGFloat(frame.ballRadius)), with: .color(colors.ball))
        }
    }

    private func circle(x: Float, y: Float, radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: CGFloat(x) - radius, y: CGFloat(y) - radius, width: radius * 2, height: radius * 2))
    }
}

/// Plan F8b: one overlay landing dot, as an accessibility element.
struct RangeLandingMarker: Equatable {
    let shotId: String
    let label: String
    let point: CGPoint
}

/// The overlay's landing dots as accessibility elements (plan F8b): VoiceOver can reach and select
/// each landing, which a sighted user taps, and UI tests can find where to tap. They don't take
/// touches themselves: a tap on the scene still goes through `RangeGestureLayer`'s tap-to-select.
struct RangeLandingMarkers: View {
    let markers: [RangeLandingMarker]
    let selectedId: String?
    let onSelect: (String) -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(Array(markers.enumerated()), id: \.offset) { _, marker in
                Color.clear
                    .frame(width: 44, height: 44)
                    .position(marker.point)
                    .accessibilityElement()
                    .accessibilityLabel(marker.label)
                    .accessibilityAddTraits(marker.shotId == selectedId ? [.isButton, .isSelected] : .isButton)
                    .accessibilityAction { onSelect(marker.shotId) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .allowsHitTesting(false)
    }
}

/// Counts rendered frames while the range animates and logs the rate (debug builds): once a second
/// and once per playback, under the `dev.openflight.companion` subsystem, category `RangeCanvas`.
/// Read it with `log stream --level debug --predicate 'category == "RangeCanvas"'`.
///
/// Plan F8b: it also times every `render`, animating or not (a gesture redraws without the
/// `TimelineView`), and logs each burst of redraws (a pinch, a pan, an orbit): the frames, their
/// rate, the average and slowest render and the overlay's size, the 200-shot overlay check.
final class RangeFrameCounter {
    #if DEBUG
    private let logger = Logger(subsystem: "dev.openflight.companion", category: "RangeCanvas")
    private var burstStart: CFTimeInterval?
    private var burstLast: CFTimeInterval = 0
    private var burstFrames = 0
    private var burstRenderSeconds = 0.0
    private var burstSlowest = 0.0
    private var burstOverlay = 0
    #endif

    /// A redraw more than this long after the previous one starts a new burst.
    private static let burstGapSeconds: CFTimeInterval = 0.25

    /// One `render` that began at `start` (`CACurrentMediaTime`) has finished.
    func rendered(start: CFTimeInterval, overlayCount: Int) {
        #if DEBUG
        let end = CACurrentMediaTime()
        if let burstStart, start - burstLast > Self.burstGapSeconds {
            logBurst(from: burstStart)
            self.burstStart = nil
        }
        if burstStart == nil {
            burstStart = start
            burstFrames = 0
            burstRenderSeconds = 0
            burstSlowest = 0
        }
        burstFrames += 1
        burstRenderSeconds += end - start
        burstSlowest = max(burstSlowest, end - start)
        burstOverlay = overlayCount
        burstLast = start
        scheduleFlush()
        #endif
    }

    #if DEBUG
    private func logBurst(from start: CFTimeInterval) {
        let seconds = burstLast - start
        guard burstFrames >= 10, seconds > 0 else { return }
        let fps = Double(burstFrames - 1) / seconds
        let average = burstRenderSeconds / Double(burstFrames) * 1_000
        let slowest = burstSlowest * 1_000
        logger.debug(
            "range redraws \(self.burstFrames) frames in \(seconds, format: .fixed(precision: 2)) s: \(fps, format: .fixed(precision: 1)) fps, render avg \(average, format: .fixed(precision: 2)) ms, max \(slowest, format: .fixed(precision: 2)) ms, overlay \(self.burstOverlay) shots"
        )
    }

    /// Logs the burst once the redraws have stopped (checked after the gap, at most one pending).
    private func scheduleFlush() {
        guard !flushScheduled else { return }
        flushScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.burstGapSeconds * 2) { [weak self] in
            guard let self else { return }
            self.flushScheduled = false
            guard let start = self.burstStart else { return }
            if CACurrentMediaTime() - self.burstLast > Self.burstGapSeconds {
                self.logBurst(from: start)
                self.burstStart = nil
            } else {
                self.scheduleFlush()
            }
        }
    }

    private var flushScheduled = false
    private var runStart: Date?
    private var runFrames = 0
    private var windowStart: Date?
    private var windowFrames = 0
    private var lastTick: Date?
    #endif

    func tick(_ now: Date) {
        #if DEBUG
        if runStart == nil { runStart = now }
        if windowStart == nil { windowStart = now }
        runFrames += 1
        windowFrames += 1
        lastTick = now
        if let windowStart, now.timeIntervalSince(windowStart) >= 1 {
            let seconds = now.timeIntervalSince(windowStart)
            logger.debug("range fps \(Double(self.windowFrames - 1) / seconds, format: .fixed(precision: 1))")
            self.windowStart = now
            windowFrames = 1
        }
        #endif
    }

    func finish() {
        #if DEBUG
        if let runStart, let lastTick, runFrames > 1 {
            let seconds = lastTick.timeIntervalSince(runStart)
            if seconds > 0 {
                logger.debug(
                    "range playback \(self.runFrames) frames in \(seconds, format: .fixed(precision: 2)) s: \(Double(self.runFrames - 1) / seconds, format: .fixed(precision: 1)) fps"
                )
            }
        }
        reset()
        #endif
    }

    func reset() {
        #if DEBUG
        runStart = nil
        runFrames = 0
        windowStart = nil
        windowFrames = 0
        lastTick = nil
        #endif
    }
}

extension CGPathSink {
    /// The Kotlin sink's `CGMutablePath` (exported to Objective-C as an opaque pointer).
    var cgPath: CGPath? {
        guard let pointer = path else { return nil }
        return Unmanaged<CGMutablePath>.fromOpaque(pointer).takeUnretainedValue()
    }
}

extension RangeColor {
    /// A shared palette colour (sRGB, straight alpha).
    var swiftUI: Color {
        Color(.sRGB, red: Double(red), green: Double(green), blue: Double(blue), opacity: Double(alpha))
    }
}

extension RangeVisualStyle {
    /// The scrim over the scene that keeps the overlaid controls readable (Android's shade).
    var shadeGradient: LinearGradient {
        LinearGradient(
            stops: shade.map { Gradient.Stop(color: $0.color.swiftUI, location: CGFloat($0.offset)) },
            startPoint: .top,
            endPoint: .bottom
        )
    }
}
