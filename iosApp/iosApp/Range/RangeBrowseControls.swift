// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Plan F8b: the replay transport or the overlay's bar at the bottom of the scene, Android's
/// `RangeBrowseBar`. Replay: Live, Prev, Play/Pause, Next, the position and the 0.5×/1×/2× speeds.
/// Overlay: Live, "Replay session" (one session's overlay), and the club filter. Every button
/// sends one of the shared `DrivingRangeEvent`s; the view model owns the logic.
struct RangeBrowseBar: View {
    let browse: RangeBrowseState
    let send: (DrivingRangeEvent) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    RangeBarButton(title: "Live", identifier: RangeTestTags.shared.LIVE) {
                        send(DrivingRangeEventReturnToLive.shared)
                    }
                    if let mode = browse.mode as? RangeModeReplay {
                        replayTransport(mode)
                    } else if let mode = browse.mode as? RangeModeOverlay {
                        overlayControls(mode)
                    }
                }
                .padding(.vertical, 2)
            }
            if browse.mode is RangeModeOverlay, browse.overlayTruncated {
                Text("Showing the newest \(RangeBrowseState.companion.OVERLAY_CAP) shots")
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier(RangeTestTags.shared.OVERLAY_TRUNCATED)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(RangeTestTags.shared.TRANSPORT)
    }

    @ViewBuilder
    private func replayTransport(_ mode: RangeModeReplay) -> some View {
        let count = browse.shots.count
        let index = Int(mode.index)
        RangeBarButton(title: "Prev", identifier: RangeTestTags.shared.PREVIOUS, isEnabled: index > 0) {
            send(DrivingRangeEventPreviousShot.shared)
        }
        RangeBarButton(
            title: browse.playing ? "Pause" : "Play",
            identifier: RangeTestTags.shared.PLAY_PAUSE,
            isEnabled: count > 0
        ) {
            send(DrivingRangeEventPlayPause.shared)
        }
        RangeBarButton(title: "Next", identifier: RangeTestTags.shared.NEXT, isEnabled: index + 1 < count) {
            send(DrivingRangeEventNextShot.shared)
        }
        Text(browse.loading ? "Loading…" : count == 0 ? "No shots" : "\(index + 1) / \(count)")
            .font(.of(.subheadline, weight: .semibold).monospacedDigit())
            .foregroundStyle(Theme.cream)
            .fixedSize()
            .accessibilityIdentifier(RangeTestTags.shared.POSITION)
        ForEach(ReplaySpeed.entries, id: \.self) { speed in
            ChipButton(label: speed.label, isSelected: browse.speed == speed, minTouchTarget: true) {
                send(DrivingRangeEventSetSpeed(speed: speed))
            }
            .accessibilityIdentifier(RangeTestTags.shared.speed(speed: speed))
        }
    }

    @ViewBuilder
    private func overlayControls(_ mode: RangeModeOverlay) -> some View {
        if let sessionId = mode.sessionId {
            RangeBarButton(title: "Replay session", identifier: RangeTestTags.shared.REPLAY_SESSION) {
                send(DrivingRangeEventStartReplay(sessionId: sessionId, index: 0))
            }
        }
        ChipButton(
            label: "All clubs",
            count: mode.club == nil && !browse.loading ? Int32(browse.overlayFlights.count) : nil,
            isSelected: mode.club == nil,
            minTouchTarget: true
        ) {
            send(DrivingRangeEventSetOverlayClub(club: nil))
        }
        .accessibilityIdentifier(RangeTestTags.shared.overlayClub(club: nil))
        ForEach(browse.overlayClubs, id: \.self) { club in
            ChipButton(label: Units.clubLabel(club), isSelected: mode.club == club, minTouchTarget: true) {
                send(DrivingRangeEventSetOverlayClub(club: club))
            }
            .accessibilityIdentifier(RangeTestTags.shared.overlayClub(club: club))
        }
    }
}

/// A capsule button on the dark scene, like the range's Exit button.
struct RangeBarButton: View {
    let title: String
    let identifier: String
    var isEnabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Theme.cream)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.black.opacity(0.6), in: Capsule())
                .overlay { Capsule().stroke(.white.opacity(0.2), lineWidth: 1) }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.45)
        .fixedSize()
        .accessibilityIdentifier(identifier)
    }
}

/// Plan F8b: the session picker, Android's `RangeSessionSheet`, from the view model's
/// `browse.sessions` (`ShotHistoryRepository.sessions()`): replay or overlay one session, or overlay
/// every session.
struct RangeSessionSheet: View {
    let sessions: [RangeSessionOption]
    let send: (DrivingRangeEvent) -> Void
    let dismiss: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Replay a session")
                    .font(.ofDisplay(.title2))
                    .foregroundStyle(Theme.cream)
                if sessions.isEmpty {
                    Text("No stored sessions yet. Hit some shots and they'll be here.")
                        .font(.of(.subheadline))
                        .foregroundStyle(Theme.creamDim)
                        .accessibilityIdentifier(RangeTestTags.shared.SESSIONS_EMPTY)
                } else {
                    Button {
                        choose(DrivingRangeEventStartOverlay(sessionId: nil, club: nil))
                    } label: {
                        Text("Overlay all sessions")
                            .font(.of(.body, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(Theme.gold)
                            .overlay { Capsule().stroke(Theme.gold.opacity(0.6), lineWidth: 1) }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier(RangeTestTags.shared.OVERLAY_ALL)

                    ForEach(sessions, id: \.id) { session in
                        sessionRow(session)
                    }
                }
            }
            .padding(20)
        }
        .background(Theme.bgCard.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier(RangeTestTags.shared.SESSION_SHEET)
    }

    private func sessionRow(_ session: RangeSessionOption) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(session.title)
                    .font(.of(.body, weight: .semibold))
                    .foregroundStyle(Theme.cream)
                Text("\(session.shotCount) shots")
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            RangeBarButton(title: "Replay", identifier: RangeTestTags.shared.replaySession(id: session.id)) {
                choose(DrivingRangeEventStartReplay(sessionId: session.id, index: 0))
            }
            RangeBarButton(title: "Overlay", identifier: RangeTestTags.shared.overlaySession(id: session.id)) {
                choose(DrivingRangeEventStartOverlay(sessionId: session.id, club: nil))
            }
        }
    }

    private func choose(_ event: DrivingRangeEvent) {
        send(event)
        dismiss()
    }
}

/// Plan F8b: the side pane on a regular width (an iPad), Android's `RangeShotList`: the replay
/// session's shots in order, or the overlay's newest first. Tapping one selects it (replay jumps to
/// it, the overlay highlights it).
struct RangeShotList: View {
    let browse: RangeBrowseState
    let onSelect: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                Eyebrow(browse.mode is RangeModeOverlay ? "OVERLAY · newest first" : "REPLAY · in order")
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)
                // By position: an "all sessions" overlay can hold two sessions' rows with one id.
                ForEach(Array(browse.shots.enumerated()), id: \.offset) { _, shot in
                    row(shot)
                }
            }
            .padding(12)
        }
        .background(Theme.bgDeep)
        .accessibilityIdentifier(RangeTestTags.shared.SHOT_LIST)
    }

    private func row(_ shot: RangeShotItem) -> some View {
        let selected = shot.id == browse.selectedShotId
        return Button {
            onSelect(shot.id)
        } label: {
            HStack(spacing: 10) {
                Text("#\(shot.number)")
                    .font(.of(.caption).monospacedDigit())
                    .foregroundStyle(Theme.creamDim)
                Text(shot.clubLabel)
                    .font(.of(.body))
                    .foregroundStyle(Theme.cream)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("\(RangeFormat.number(shot.carryYards, decimals: 0)) yd")
                    .font(.of(.body, weight: .semibold).monospacedDigit())
                    .foregroundStyle(selected ? Theme.gold : Theme.cream)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(minHeight: 44)
            .background(selected ? Theme.bgHover : .clear, in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(RangeTestTags.shared.shot(id: shot.id))
    }
}

/// Plan F8b chips above the transport, Android's `BrowseChips`: "New shot · Return to live",
/// "Reset view" while the user has moved the camera, and the estimated total once the ball is down.
/// Plan F8d-B: under them, "Simulate shot" on a mock Pi (`canSimulate`) and why it last failed.
struct RangeBrowseChips: View {
    let state: DrivingRangeUiState
    let send: (DrivingRangeEvent) -> Void

    var body: some View {
        let browse = state.browse
        let rollOut = showsRollOut ? state.rollOut : nil
        VStack(alignment: .trailing, spacing: 6) {
            if browse.newLiveShot || browse.userTransformed || rollOut != nil {
                chips(browse: browse, rollOut: rollOut)
            }
            // Plan F8d-B: a `--mock` Pi over Wi-Fi flies a new simulated shot through the live
            // path. On its own row, so the chips above keep their width on an iPhone.
            if state.canSimulate || state.simulateError != nil {
                simulateRow
            }
        }
    }

    private func chips(browse: RangeBrowseState, rollOut: RangeRollOut?) -> some View {
        HStack(spacing: 8) {
            Spacer(minLength: 0)
            if browse.newLiveShot {
                ChipButton(label: "New shot · Return to live", isSelected: true, minTouchTarget: true) {
                    send(DrivingRangeEventReturnToLive.shared)
                }
                .accessibilityIdentifier(RangeTestTags.shared.NEW_LIVE_SHOT)
            }
            if browse.userTransformed {
                ChipButton(label: "Reset view", isSelected: false, minTouchTarget: true) {
                    send(DrivingRangeEventResetView.shared)
                }
                .background(.black.opacity(0.55), in: Capsule())
                .accessibilityIdentifier(RangeTestTags.shared.RESET_VIEW)
            }
            if let rollOut {
                Text(Self.rollOutSummary(rollOut))
                    .font(.of(.caption, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.cream)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(.black.opacity(0.55), in: Capsule())
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityIdentifier(RangeTestTags.shared.ROLL_OUT)
            }
        }
    }

    /// "Simulate shot" (Android's `BrowseChips`), with why the last request failed before it.
    private var simulateRow: some View {
        HStack(spacing: 8) {
            Spacer(minLength: 0)
            if let error = state.simulateError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.of(.caption, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .lineLimit(2)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.55), in: Capsule())
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier(RangeTestTags.shared.SIMULATE_ERROR)
            }
            if state.canSimulate {
                Button {
                    send(DrivingRangeEventSimulateShot.shared)
                } label: {
                    Label("Simulate shot", systemImage: "sparkles")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Theme.gold)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.6), in: Capsule())
                        .overlay { Capsule().stroke(.white.opacity(0.2), lineWidth: 1) }
                        .frame(minHeight: 44)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Asks the mock Pi for a new shot")
                .accessibilityIdentifier(RangeTestTags.shared.SIMULATE)
            }
        }
    }

    private var showsRollOut: Bool {
        state.phase is RangePhaseLanded || state.phase is RangePhaseWaiting
    }

    /// "Total est. 285 yd · roll 21": total and roll are always estimates (plan §0.2), like
    /// Android's `rollOutSummary`.
    static func rollOutSummary(_ rollOut: RangeRollOut) -> String {
        "Total est. \(Int(rollOut.totalYards.rounded())) yd · roll \(Int(rollOut.rollYards.rounded()))"
            + (rollOut.carryEstimated ? " · carry est." : "")
    }
}
