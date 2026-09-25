// SPDX-License-Identifier: AGPL-3.0-or-later
import AVKit
import KMPNativeCoroutinesAsync
import Shared
import SwiftUI
import UIKit

/// The Pi's camera (plan R8c), over the shared `CameraViewModel`: a preview of the high-speed
/// camera polled while this screen is visible, the capture settings, and the shots whose captures
/// can be replayed in `AVPlayer`. Wi-Fi only. Android's `CameraScreen.kt` renders the same state;
/// polish is plan R8f.
struct CameraView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().cameraViewModel())
    @State private var frame: UIImage?
    @State private var message: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        CameraContent(state: host.state, frame: frame, send: host.send)
            .navigationTitle("Camera")
            .messageBanner($message)
            .task {
                await host.collect(host.viewModel.sideEffects) { effect in
                    if let note = effect as? CameraEffectMessage { message = note.text }
                }
            }
            // Collecting the stills is what makes the VM poll the Pi, so collect only while this
            // screen is on screen and the app is active. The Kotlin flow is conflated and the
            // async sequence asks for the next still only after this loop took the previous one.
            .task(id: scenePhase == .active) {
                guard scenePhase == .active else {
                    frame = nil
                    return
                }
                do {
                    for try await jpeg in asyncSequence(for: host.viewModel.jpegFrames) {
                        let image = await Task.detached(priority: .userInitiated) {
                            UIImage(data: jpeg)?.preparingForDisplay()
                        }.value
                        if let image { frame = image }
                    }
                } catch {
                    // Cancellation (left the screen). Failures reach the state.
                }
            }
    }
}

struct CameraContent: View {
    let state: CameraUiState
    let frame: UIImage?
    let send: (CameraEvent) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                feed
                capture
                replays
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .screenBackground()
        .sheet(isPresented: Binding(
            get: { state.replay is CameraReplayStateReady },
            set: { shown in if !shown { send(CameraEventDismissReplay.shared) } }
        )) {
            if let ready = state.replay as? CameraReplayStateReady, let url = URL(string: ready.videoUrl) {
                ReplayPlayer(url: url, mirrored: ready.mirrorHorizontal)
            }
        }
    }

    // MARK: Feed

    private var phaseCopy: (title: String, body: String, icon: String) {
        switch state.phase {
        case .offline:
            ("Camera Offline",
             "The camera needs the Pi's live Wi-Fi session (\(state.availability.disabledReason ?? "")).",
             "wifi.slash")
        case .notEnabled:
            ("Camera Capture Off",
             "Start the Pi's server with high-speed camera capture to see its view here.",
             "video.slash")
        case .notRunning:
            ("Camera Not Running", "Capture is configured, but the camera isn't producing images.", "video.slash")
        case .error:
            ("Preview Unavailable", state.previewError ?? CameraViewModel.companion.PREVIEW_FAILED,
             "exclamationmark.triangle")
        default:
            ("", "", "")
        }
    }

    @ViewBuilder
    private var feed: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20).fill(.black.opacity(0.45))
            if state.phase == .live, let frame {
                Image(uiImage: frame)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .accessibilityLabel("Camera preview")
            } else if state.phase == .loading || state.phase == .live {
                ProgressView().tint(Theme.gold)
            } else {
                let copy = phaseCopy
                VStack(spacing: 10) {
                    Image(systemName: copy.icon)
                        .font(.system(size: 34))
                        .foregroundStyle(Theme.creamMuted)
                        .accessibilityHidden(true)
                    Text(copy.title)
                        .font(.ofDisplay(.title2))
                        .accessibilityIdentifier("camera.phaseTitle")
                    Text(copy.body)
                        .font(.of(.subheadline))
                        .foregroundStyle(Theme.creamDim)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .accessibilityElement(children: .contain)
            }
        }
        .aspectRatio(4 / 3, contentMode: .fit)
        .overlay { RoundedRectangle(cornerRadius: 20).stroke(Theme.cream.opacity(0.1), lineWidth: 1) }
        .accessibilityIdentifier("camera.feed")
    }

    // MARK: Capture

    private var captureStatus: String {
        guard let settings = state.settings else { return "Not reported yet" }
        if !settings.available { return "Not enabled on the Pi" }
        if settings.armed?.boolValue == true { return "Armed" }
        if settings.running?.boolValue == true { return "Running" }
        return "Stopped"
    }

    private var capture: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("CAPTURE")
            Text(captureStatus)
                .font(.of(.headline, weight: .semibold))
                .accessibilityIdentifier("camera.captureStatus")
            if let summary = state.captureSummary {
                Text(summary).font(.of(.subheadline)).foregroundStyle(Theme.creamDim)
            }
            if let error = state.settings?.error {
                Text(error).font(.of(.footnote, weight: .medium)).foregroundStyle(Theme.danger)
            }
            Button("Refresh") { send(CameraEventRefreshSettings.shared) }
                .buttonStyle(.bordered)
                .tint(Theme.gold)
                .disabled(!state.availability.isAvailable)
                .accessibilityIdentifier("camera.refresh")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    // MARK: Replays

    private var replays: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("REPLAYS")
            if state.replays.isEmpty {
                Text("Shots with a high-speed capture appear here.")
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
            }
            ForEach(state.replays, id: \.replayId) { row in
                HStack {
                    Text(label(row)).font(.of(.subheadline))
                    Spacer()
                    if let preparing = state.replay as? CameraReplayStatePreparing, preparing.replayId == row.replayId {
                        ProgressView().tint(Theme.gold)
                    } else {
                        Button("Play") { send(CameraEventPlayReplay(replayId: row.replayId)) }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.gold)
                            .disabled(!state.availability.isAvailable || state.replay is CameraReplayStatePreparing)
                            .accessibilityIdentifier("camera.replay.\(row.replayId)")
                    }
                }
            }
            if let failed = state.replay as? CameraReplayStateFailed {
                Text(failed.message)
                    .font(.of(.footnote, weight: .medium))
                    .foregroundStyle(Theme.danger)
                    .accessibilityIdentifier("camera.replayError")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func label(_ row: ReplayRow) -> String {
        var parts: [String] = []
        if let number = row.shotNumber { parts.append("#\(number.intValue)") }
        if let club = row.club { parts.append(club) }
        if let speed = row.ballSpeedMph { parts.append(String(format: "%.1f mph", speed.doubleValue)) }
        return parts.joined(separator: " · ")
    }
}

/// A prepared shot replay in `AVPlayer` (the Pi serves the MP4 with HTTP Range support).
private struct ReplayPlayer: View {
    let url: URL
    let mirrored: Bool
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: player)
            .scaleEffect(x: mirrored ? -1 : 1, y: 1)
            .onAppear {
                let player = AVPlayer(url: url)
                self.player = player
                player.play()
            }
            .onDisappear { player?.pause() }
            .accessibilityIdentifier("camera.player")
    }
}

/// A switch bound to a Pi command: it flips by asking the VM, and shows why it's disabled.
struct ToggleRow: View {
    let label: String
    var detail: String?
    let isOn: Bool
    let availability: PiFeatureAvailability
    let identifier: String
    let toggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: Binding(get: { isOn }, set: { _ in toggle() })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.of(.body, weight: .semibold))
                    if let detail {
                        Text(detail).font(.of(.footnote)).foregroundStyle(Theme.creamDim)
                    }
                }
            }
            .tint(Theme.gold)
            .disabled(!availability.isAvailable)
            .accessibilityIdentifier(identifier)
            if let reason = availability.disabledReason {
                DisabledReason(reason: reason)
                    .accessibilityIdentifier("\(identifier).reason")
            }
        }
    }
}
