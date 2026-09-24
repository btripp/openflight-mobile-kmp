// SPDX-License-Identifier: AGPL-3.0-or-later
import KMPNativeCoroutinesAsync
import Shared
import SwiftUI
import UIKit

/// The Pi's camera (plan R6c), over the shared `CameraViewModel`: the live MJPEG feed while
/// streaming, otherwise the web UI's `CameraFeed.tsx` state (offline, not available, disabled,
/// paused, stream error), plus ball detection and the two toggles. Wi-Fi only. Android's
/// `CameraScreen.kt` renders the same state.
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
            // Collect frames only while streaming and on screen. The Kotlin flow is conflated and
            // the async sequence asks for the next frame only after this loop took the previous
            // one, so a slow decode drops frames instead of queueing them; only the newest image
            // is kept.
            .task(id: host.state.phase == .streaming && scenePhase == .active) {
                guard host.state.phase == .streaming, scenePhase == .active else {
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
                    // Cancellation (left the screen or the stream stopped). Failures reach the state.
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
                ballDetection
                controls
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .screenBackground()
    }

    // MARK: Feed

    private var phaseCopy: (title: String, body: String, icon: String) {
        switch state.phase {
        case .offline:
            ("Camera Offline",
             "The camera needs the Pi's live Wi-Fi session (\(state.availability.disabledReason ?? "")).",
             "wifi.slash")
        case .unavailable:
            ("Camera Not Available",
             "Start the Pi's server with the --camera flag to enable camera support.",
             "video.slash")
        case .disabled:
            ("Camera Disabled", "Turn on ball detection to start the camera.", "video.slash")
        case .paused:
            ("Stream Paused", "Ball detection is active. Turn on the live stream to watch the feed.", "pause.circle")
        case .streamError:
            ("Stream Error", state.streamError ?? CameraViewModel.companion.STREAM_FAILED, "exclamationmark.triangle")
        default:
            ("", "", "")
        }
    }

    @ViewBuilder
    private var feed: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20).fill(.black.opacity(0.45))
            if state.phase == .streaming {
                if let frame {
                    Image(uiImage: frame)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 20))
                        .accessibilityLabel("Live camera feed")
                } else {
                    ProgressView().tint(Theme.gold)
                }
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
                    if let error = state.cameraError {
                        Text(error)
                            .font(.of(.footnote, weight: .medium))
                            .foregroundStyle(Theme.danger)
                            .multilineTextAlignment(.center)
                    }
                    if state.phase == .streamError {
                        Button("Retry") { send(CameraEventRetryStream.shared) }
                            .buttonStyle(.bordered)
                            .tint(Theme.gold)
                            .accessibilityIdentifier("camera.retry")
                    }
                }
                .padding(24)
                .accessibilityElement(children: .contain)
            }
        }
        .aspectRatio(4 / 3, contentMode: .fit)
        .overlay { RoundedRectangle(cornerRadius: 20).stroke(Theme.cream.opacity(0.1), lineWidth: 1) }
        .accessibilityIdentifier("camera.feed")
    }

    // MARK: Ball detection

    private var dotColor: Color {
        if !state.enabled { return Theme.neutral }
        return state.ballDetected ? Theme.success : Theme.warning
    }

    private var ballDetection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("BALL DETECTION")
            HStack(spacing: 10) {
                Circle().fill(dotColor).frame(width: 12, height: 12)
                Text(state.statusText)
                    .font(.of(.headline, weight: .semibold))
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("camera.ballStatus")
            if state.enabled {
                ProgressView(value: Double(state.ballConfidencePercent), total: 100)
                    .tint(Theme.gold)
                Text("Confidence \(state.ballConfidencePercent)%")
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
            }
        }
        .card()
    }

    // MARK: Controls

    private var controls: some View {
        VStack(alignment: .leading, spacing: 14) {
            Eyebrow("CONTROLS")
            ToggleRow(
                label: "Ball detection",
                detail: "Runs the camera and looks for the ball",
                isOn: state.enabled,
                availability: state.toggleCamera,
                identifier: "camera.toggleCamera"
            ) { send(CameraEventToggleCamera.shared) }
            Divider().overlay(Theme.cream.opacity(0.1))
            ToggleRow(
                label: "Live stream",
                detail: "Shows the camera's view above",
                isOn: state.streaming,
                availability: state.toggleStream,
                identifier: "camera.toggleStream"
            ) { send(CameraEventToggleStream.shared) }
        }
        .card()
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
