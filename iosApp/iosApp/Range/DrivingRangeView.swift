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
struct DrivingRangeView: View {
    let autoplay: Bool

    @StateObject private var host = ViewModelHost(KoinHelper().drivingRangeViewModel())
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
            }
        )
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if autoplay {
                host.send(DrivingRangeEventReplay.shared)
            }
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

/// The stateless range: the RealityKit scene under a shading gradient, the metrics overlay, the
/// "Driving Range Ready" card before the first shot, and the exit / status / replay controls.
struct DrivingRangeContent: View {
    let state: DrivingRangeUiState
    let reduceMotion: Bool
    let send: (DrivingRangeEvent) -> Void
    let onExit: () -> Void

    var body: some View {
        GeometryReader { geometry in
            let isLandscape = geometry.size.width > geometry.size.height

            ZStack {
                RangeSceneView(
                    flight: state.activeFlight,
                    reduceMotion: reduceMotion,
                    onFlightCompleted: { send(DrivingRangeEventFlightCompleted.shared) }
                )
                .ignoresSafeArea()

                LinearGradient(
                    colors: [.black.opacity(0.38), .clear, .black.opacity(0.60)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)

                RangeMetricsOverlay(
                    state: state,
                    isLandscape: isLandscape,
                    onSelectClub: { send(DrivingRangeEventClubSelected(club: $0)) }
                )

                if state is DrivingRangeUiStateReady {
                    waitingCard
                }

                controls
                    .padding(.horizontal, 14)
                    .padding(.top, 10)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .foregroundStyle(Theme.cream)
        .preferredColorScheme(.dark)
    }

    private var controls: some View {
        HStack(spacing: 10) {
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

            Spacer()

            Label(state.phase.label, systemImage: statusSymbol)
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(.black.opacity(0.58), in: Capsule())
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(RangeTestTags.shared.STATUS)

            if DrivingRangeUiStateKt.canReplay(state) {
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
        }
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
