// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Plan F8a2t: the Settings › Practice "Shot trail" preview, Android's `ShotTrailPreviewCanvas`. The
/// range's own `RangeCanvasView` and shared `ShotTrail` fly `ShotTrailPreview`'s 7-iron on a loop in
/// the chosen style over the chosen theme, with its earlier shots when "Keep last shots" asks for
/// them and the landing effect at each landing. Under reduced motion it shows the landed shot, still.
/// It has no gesture layer, so the form scrolls over it.
struct ShotTrailPreviewView: View {
    let style: ShotTrailStyle
    let keepLast: Int32
    let landingEffect: LandingEffect
    let theme: RangeThemeSetting

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var playbackId: Int64 = 1
    @State private var flight: ActiveFlight?
    @State private var trail: RangeTrailState?

    var body: some View {
        RangeCanvasView(
            flight: flight,
            cameraMode: .fixed,
            reduceMotion: reduceMotion,
            theme: RangeTheme.companion.of(setting: theme),
            view: ShotTrailPreview.shared.VIEW,
            freezeProgress: reduceMotion ? 1 : nil,
            onFlightCompleted: { loop() },
            trail: trail,
            sceneIdentifier: RangeTestTags.shared.TRAIL_PREVIEW
        )
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel("Shot trail preview")
        .task {
            // The preview flights are simulated once; the first call does it off the main thread.
            if flight == nil {
                let first = await Task.detached { ShotTrailPreview.shared.flight(playbackId: 1) }.value
                flight = first
            }
            updateTrail()
        }
        .onChange(of: style) { updateTrail() }
        .onChange(of: keepLast) { updateTrail() }
        .onChange(of: landingEffect) { updateTrail() }
    }

    /// One stable state per choice: the renderer compares it by identity.
    private func updateTrail() {
        guard flight != nil else { return }
        trail = ShotTrailPreview.shared.trail(style: style, keepLast: keepLast, landingEffect: landingEffect)
    }

    private func loop() {
        guard !reduceMotion else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(ShotTrailPreview.shared.LOOP_PAUSE_MILLIS) * 1_000_000)
            playbackId += 1
            flight = ShotTrailPreview.shared.flight(playbackId: playbackId)
        }
    }
}
