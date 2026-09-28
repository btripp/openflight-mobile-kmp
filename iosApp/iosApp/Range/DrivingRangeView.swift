// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

extension DrivingRangeViewModel: SharedViewModel {}

/// The driving range destination, ported from the reference `DrivingRangeView.swift`. It owns the
/// shared `DrivingRangeViewModel`, whose phase machine (newest pending shot wins, 1.25 s landing
/// dwell) replaces the reference's local one, and renders its `DrivingRangeUiState`.
///
/// Like the reference and Android's `DrivingRangeRoute`, the range suspends (cancelling the flight
/// and any queued shot) when the scene leaves the foreground or the screen goes away, and Exit
/// suspends before leaving.
///
/// - Parameter autoplay: fly the displayed shot when the range opens (the `--preview-flight` hook).
/// - Parameter launch: plan F8d-B ("View on range"): open replaying this stored session, paused on
///   its shot when it names one (the shared `DrivingRangeEvent.Launch`). Sent once per screen.
struct DrivingRangeView: View {
    let autoplay: Bool
    var launch: RangeLaunch?

    @StateObject private var host = ViewModelHost(KoinHelper().drivingRangeViewModel())
    @State private var launched = false
    /// Debug launch hooks (plan F8c2): `--range-realitykit`, `--range-freeze-progress`.
    private let launchOptions = KoinHelper().launchOptions()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        DrivingRangeContent(
            state: host.state,
            reduceMotion: reduceMotion,
            send: host.send,
            onExit: {
                host.viewModel.suspend()
                dismiss()
            },
            usesRealityKit: launchOptions.rangeRealityKit,
            freezeProgress: launchOptions.rangeFreezeProgress?.doubleValue
        )
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            // Reduced motion fixes the camera and locks its toggle (plan R7a/R7b).
            host.send(DrivingRangeEventReduceMotionChanged(enabled: reduceMotion))
            if let launch, !launched {
                launched = true
                host.send(DrivingRangeEventLaunch(launch: launch))
            } else if autoplay {
                host.send(DrivingRangeEventReplay.shared)
            }
        }
        .onChange(of: reduceMotion) {
            host.send(DrivingRangeEventReduceMotionChanged(enabled: reduceMotion))
        }
        .onChange(of: scenePhase) {
            if scenePhase != .active {
                host.viewModel.suspend()
            }
        }
        .onDisappear {
            host.viewModel.suspend()
        }
    }
}

/// The stateless range: the scene under a shading gradient, the metrics overlay, the
/// "Driving Range Ready" card before the first shot, and the exit / status / history / replay
/// controls.
///
/// The scene is the shared-geometry `RangeCanvasView` (plan F8c2, ADR 0002). The previous
/// RealityKit `RangeSceneView` stays behind `usesRealityKit` (`--range-realitykit`) for one release.
///
/// Plan F8b (Android's F8a1 parity): History opens the session picker (`RangeSessionSheet`); in
/// replay or overlay the transport (`RangeBrowseBar`) sits at the bottom, above it the "New shot ·
/// Return to live", "Reset view" and estimated-total chips; the scene takes pinch, pan, orbit,
/// double-tap and tap-to-select gestures. On a regular width (an iPad) replay and overlay add the
/// shot list as a side pane (`RangeShotList`), like Android's `OfListDetailPane`.
struct DrivingRangeContent: View {
    let state: DrivingRangeUiState
    let reduceMotion: Bool
    let send: (DrivingRangeEvent) -> Void
    let onExit: () -> Void
    var usesRealityKit = false
    /// Debug (`--range-freeze-progress`): hold every flight at this playback progress.
    var freezeProgress: Double?

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var showsSessions = false
    /// Plan F8f: the range quick settings (a sheet on an iPhone, a side panel on an iPad).
    @State private var showsQuickSettings = false
    /// The controls' measured height: one row, or two when the status pill drops under the buttons.
    @State private var controlsHeight: CGFloat = 44
    /// Plan F8a2p: the overlaid UI's frames and the canvas's, both global, for the scene's obstructions.
    @State private var obstructionFrames: [String: CGRect] = [:]
    @State private var canvasFrame: CGRect = .zero

    /// The side pane's width on a regular width: Android's 30 %, within readable bounds.
    private static let shotListFraction: CGFloat = 0.3

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                if horizontalSizeClass == .regular && !state.browse.isLive {
                    RangeShotList(browse: state.browse, numbers: state.camera.numbers) {
                        send(DrivingRangeEventSelectShot(shotId: $0))
                    }
                    .frame(width: min(max(geometry.size.width * Self.shotListFraction, 260), 380))
                    Divider()
                }
                stage
                // Plan F8f: on an iPad the quick settings sit beside the scene (and the shot list).
                if showsQuickSettingsPanel {
                    Divider()
                    RangeQuickSettingsView(state: state, send: send, isPanel: true) { showsQuickSettings = false }
                        .frame(width: Self.quickSettingsWidth)
                        .transition(.move(edge: .trailing))
                }
            }
        }
        .foregroundStyle(Theme.cream)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showsSessions) {
            RangeSessionSheet(sessions: state.browse.sessions, send: send) { showsSessions = false }
        }
        // Plan F8f: on an iPhone, a sheet over the scene; swipe down or tap outside to close.
        .sheet(isPresented: quickSettingsSheet) {
            RangeQuickSettingsView(state: state, send: send)
                .presentationDetents([.fraction(0.6), .large])
                .presentationDragIndicator(.visible)
                .presentationBackground(Theme.bgCard)
        }
    }

    /// The quick settings width beside the scene on an iPad.
    private static let quickSettingsWidth: CGFloat = 340

    private var showsQuickSettingsPanel: Bool {
        showsQuickSettings && horizontalSizeClass == .regular
    }

    private var quickSettingsSheet: Binding<Bool> {
        Binding(
            get: { showsQuickSettings && horizontalSizeClass != .regular },
            set: { if !$0 { showsQuickSettings = false } }
        )
    }

    /// The scene with its overlays: everything but the iPad side pane.
    private var stage: some View {
        GeometryReader { geometry in
            let isLandscape = geometry.size.width > geometry.size.height
            // Plan F1c: the overlay docks to the side in regular landscape (an iPad), instead of
            // spanning the top and bottom of the scene, so more of the flight stays clear.
            let docksToSide = horizontalSizeClass == .regular && isLandscape
            let browse = state.browse

            ZStack {
                scene
                    .ignoresSafeArea()

                state.camera.theme.style.shadeGradient
                    .ignoresSafeArea()
                    .allowsHitTesting(false)

                VStack(spacing: 8) {
                    RangeMetricsOverlay(
                        state: state,
                        isLandscape: isLandscape,
                        docksToSide: docksToSide,
                        // Clear of the controls (10 pt top padding plus an 18 pt gap), however
                        // many rows they take.
                        topInset: controlsHeight + 28,
                        onSelectClub: { send(DrivingRangeEventClubSelected(club: $0)) }
                    )
                    .frame(maxHeight: .infinity)

                    Group {
                        RangeBrowseChips(state: state, send: send)
                            .rangeObstruction("chips")
                        if !browse.isLive {
                            RangeBrowseBar(browse: browse, send: send)
                                .rangeObstruction("browseBar")
                        }
                    }
                    .padding(.horizontal, 14)
                }
                .padding(.bottom, browse.isLive ? 0 : 10)

                if state is DrivingRangeUiStateReady && browse.isLive {
                    waitingCard
                        .rangeObstruction("ready")
                }

                controls
                    .padding(.horizontal, 14)
                    .padding(.top, 10)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .onPreferenceChange(RangeObstructionKey.self) { frames in
                obstructionFrames = frames
            }
        }
    }

    /// Plan F8a2p: the overlaid UI's frames in the canvas's points (the canvas runs under the safe area).
    private var obstructions: [CGRect] {
        obstructionFrames.values
            .map { $0.offsetBy(dx: -canvasFrame.minX, dy: -canvasFrame.minY) }
            .sorted { ($0.minY, $0.minX) < ($1.minY, $1.minX) }
    }

    @ViewBuilder
    private var scene: some View {
        if usesRealityKit {
            RangeSceneView(
                flight: state.activeFlight,
                cameraMode: state.cameraMode,
                reduceMotion: reduceMotion,
                onFlightCompleted: { send(DrivingRangeEventFlightCompleted.shared) }
            )
        } else {
            let browse = state.browse
            RangeCanvasView(
                flight: state.activeFlight,
                cameraMode: state.cameraMode,
                reduceMotion: reduceMotion,
                theme: state.camera.theme,
                view: browse.view,
                rollOut: state.rollOut,
                overlay: browse.overlayFlights,
                overlayMode: state.mode is RangeModeOverlay,
                selectedOverlayId: state.mode is RangeModeOverlay ? browse.selectedShotId : nil,
                freezeProgress: freezeProgress,
                onFlightCompleted: { send(DrivingRangeEventFlightCompleted.shared) },
                onViewChanged: { send(DrivingRangeEventViewChanged(view: $0)) },
                onResetView: { send(DrivingRangeEventResetView.shared) },
                onSelectLanding: { send(DrivingRangeEventSelectShot(shotId: $0)) },
                obstructions: obstructions,
                trail: state.camera.trail
            )
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { canvasFrame = $0 }
        }
    }

    /// Exit, the status pill, Follow/Fixed, History and Replay in one row when they fit at their
    /// natural widths. Otherwise (a compact iPhone, large text, all three trailing buttons showing)
    /// the pill drops to its own line under the buttons instead of being squeezed into a column of
    /// letters (F8d-B), and wraps to two lines at most.
    private var controls: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                exitButton
                    .rangeObstruction("exit")
                Spacer(minLength: 0)
                statusPill
                    .fixedSize()
                    .rangeObstruction("status")
                trailingButtons
                    .rangeObstruction("buttons")
            }
            VStack(alignment: .trailing, spacing: 8) {
                HStack(spacing: 10) {
                    exitButton
                        .rangeObstruction("exit")
                    Spacer(minLength: 0)
                    trailingButtons
                        .rangeObstruction("buttons")
                }
                statusPill
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .rangeObstruction("status")
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { controlsHeight = $0 }
    }

    private var statusPill: some View {
        Label(state.phase.label, systemImage: statusSymbol)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(.black.opacity(0.58), in: Capsule())
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(RangeTestTags.shared.STATUS)
    }

    private var trailingButtons: some View {
        HStack(spacing: 10) {
            cameraToggle

            historyButton

            if DrivingRangeUiStateKt.canReplay(state) {
                replayButton
            }

            quickSettingsButton
        }
        .fixedSize()
    }

    /// Plan F8f: the gear that opens (or, on an iPad, closes) the range quick settings.
    private var quickSettingsButton: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { showsQuickSettings.toggle() }
        } label: {
            Image(systemName: "gearshape")
                .font(.subheadline.weight(.bold))
                .frame(width: 38, height: 38)
                .background(.black.opacity(0.6), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.2), lineWidth: 1)
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Range settings")
        .accessibilityHint("Show, trail, view and numbers, without leaving the range")
        .accessibilityIdentifier(RangeTestTags.shared.QUICK_SETTINGS)
    }

    private var exitButton: some View {
        Button(action: onExit) {
            Label("Exit", systemImage: "xmark")
                .font(.subheadline.weight(.bold))
                .padding(.horizontal, 13)
                .padding(.vertical, 10)
                .background(.black.opacity(0.6), in: Capsule())
                .overlay {
                    Capsule().stroke(.white.opacity(0.2), lineWidth: 1)
                }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(RangeTestTags.shared.EXIT)
    }

    private var replayButton: some View {
        Button {
            send(DrivingRangeEventReplay.shared)
        } label: {
            Image(systemName: "arrow.counterclockwise")
                .font(.subheadline.weight(.bold))
                .frame(width: 38, height: 38)
                .background(.black.opacity(0.6), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.2), lineWidth: 1)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Replay shot")
        .accessibilityIdentifier(RangeTestTags.shared.REPLAY)
    }

    /// Plan F8b: opens the session picker (Android's "History" button), icon-only so the row fits
    /// an iPhone's width.
    private var historyButton: some View {
        Button {
            showsSessions = true
        } label: {
            Image(systemName: "clock.arrow.circlepath")
                .font(.subheadline.weight(.bold))
                .frame(width: 38, height: 38)
                .background(.black.opacity(0.6), in: Circle())
                .overlay {
                    Circle().stroke(.white.opacity(0.2), lineWidth: 1)
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("History")
        .accessibilityHint("Replay or overlay a stored session")
        .accessibilityIdentifier(RangeTestTags.shared.HISTORY)
    }

    /// The camera-mode button (plan R7b, like Android's): shows the camera in use, "Follow" or
    /// "Fixed", and switches to the other one; the choice persists in the shared settings. Disabled
    /// (and fixed) while the system asks for reduced motion.
    private var cameraToggle: some View {
        let label = state.cameraMode == RangeCameraMode.follow ? "Follow" : "Fixed"
        let locked = state.cameraModeLocked
        return Button {
            send(DrivingRangeEventToggleCameraMode.shared)
        } label: {
            Label(label, systemImage: locked ? "video.slash" : "video")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(locked ? Theme.creamDim : Theme.cream)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(.black.opacity(0.6), in: Capsule())
                .overlay {
                    Capsule().stroke(.white.opacity(0.2), lineWidth: 1)
                }
        }
        .buttonStyle(.plain)
        .disabled(locked)
        .accessibilityLabel("\(label) camera")
        .accessibilityValue(locked ? "Fixed by reduced motion" : "")
        .accessibilityHint(locked ? "" : "Switches between following the ball and the fixed tee camera")
        .accessibilityIdentifier(RangeTestTags.shared.CAMERA_MODE)
    }

    private var waitingCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "figure.golf")
                .font(.system(size: 38, weight: .medium))
                .foregroundStyle(Theme.success)
            Text("Driving Range Ready")
                .font(.title2.bold())
            Text("Hit a shot and its flight will appear here.")
                .font(.callout)
                .foregroundStyle(Theme.creamDim)
        }
        .multilineTextAlignment(.center)
        .padding(24)
        .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 22))
        .overlay {
            RoundedRectangle(cornerRadius: 22)
                .stroke(.white.opacity(0.16), lineWidth: 1)
        }
        .padding(24)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(RangeTestTags.shared.READY_CARD)
    }

    private var statusSymbol: String {
        switch state.phase {
        case is RangePhaseWaiting: "circle.dotted"
        case is RangePhasePreparing: "waveform.path.ecg"
        case is RangePhaseFlying: "smallcircle.filled.circle"
        case is RangePhaseLanded: "checkmark.circle.fill"
        default: "exclamationmark.triangle.fill" // RangePhaseUnavailable
        }
    }
}

/// Plan F8a2p: the frames (global) of the UI laid over the range, by name, which the scene keeps its
/// yardage labels and far markers clear of.
struct RangeObstructionKey: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { _, next in next }
    }
}

extension View {
    /// Plan F8a2p: reports this view's frame as a range obstruction named `key` while it's shown.
    func rangeObstruction(_ key: String) -> some View {
        background {
            GeometryReader { geometry in
                Color.clear.preference(key: RangeObstructionKey.self, value: [key: geometry.frame(in: .global)])
            }
        }
    }
}
