// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The dashboard, ported from the reference `ContentView.swift`. The layout and copy are the
/// reference's; the state is the shared `DashboardViewModel`'s `DashboardUiState` instead of
/// `BluetoothManager`/`WiFiShotClient`, so both platforms render the same state and send the same
/// events.
struct DashboardView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().dashboardViewModel())
    /// Bumped on every `DashboardEffect.NewShot`: replays the gold flash and fires the haptic.
    @State private var newShots = 0

    var body: some View {
        DashboardContent(state: host.state, send: host.send, shotFlashes: newShots)
            .toolbar(.hidden, for: .navigationBar)
            .sensoryFeedback(.impact(weight: .medium), trigger: newShots)
            .task {
                await host.collect(host.viewModel.sideEffects) { effect in
                    if effect is DashboardEffectNewShot {
                        newShots += 1
                    }
                }
            }
    }
}

/// The stateless dashboard: renders a `DashboardUiState` and reports intents through `send`.
struct DashboardContent: View {
    let state: DashboardUiState
    let send: (DashboardEvent) -> Void
    var shotFlashes = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var connection: ConnectionPanelState { state.connection }
    private var live: DashboardUiStateLive? { state as? DashboardUiStateLive }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 20) {
                    header
                    connectionCard

                    if let live {
                        ShotCard(shot: live.latest, units: live.units, enrichment: live.latestEnrichment)
                            .id(live.latest.eventId)
                            .overlay { ShotFlash(trigger: shotFlashes) }
                            .transition(.opacity)

                        if !live.clubChips.isEmpty {
                            ClubChipsCard(chips: live.clubChips)
                        }

                        if !live.previous.isEmpty {
                            ShotHistoryCard(shots: live.previous, units: live.units)
                                .transition(.opacity)
                        }
                    } else {
                        emptyState
                    }
                }
                .padding(20)
                .animation(
                    reduceMotion ? nil : .easeInOut(duration: 0.25),
                    value: shotIds
                )
            }
        }
        .foregroundStyle(Theme.cream)
    }

    private var shotIds: [String] {
        guard let live else { return [] }
        return [live.latest.eventId] + live.previous.map(\.eventId)
    }

    // MARK: Header

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("OPENFLIGHT")
                    .font(.ofEyebrow)
                    .tracking(2.4)
                    .foregroundStyle(Theme.gold)
                Text("Launch Monitor")
                    .font(.ofDisplay(.largeTitle))
            }
            Spacer()
            NavigationLink(value: AppRoute.range) {
                Label("Range", systemImage: "mountain.2.fill")
                    .font(.of(.subheadline, weight: .bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Theme.gold.opacity(0.16), in: Capsule())
                    .overlay {
                        Capsule().stroke(Theme.gold.opacity(0.42), lineWidth: 1)
                    }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Theme.gold)
            .accessibilityIdentifier(DashboardTestTags.shared.RANGE)
            Image(systemName: "figure.golf")
                .font(.system(size: 34))
                .foregroundStyle(Theme.gold)
                .accessibilityHidden(true)
        }
    }

    // MARK: Connection card

    private var transportBinding: Binding<TransportType> {
        Binding(
            get: { connection.transport },
            set: { send(DashboardEventTransportChanged(transport: $0)) }
        )
    }

    private var hostBinding: Binding<String> {
        Binding(
            get: { connection.hostText },
            set: { send(DashboardEventHostEdited(text: $0)) }
        )
    }

    private var connectionCard: some View {
        VStack(spacing: 14) {
            Picker("Transport", selection: transportBinding) {
                ForEach(TransportType.entries, id: \.self) { option in
                    Text(option.label_).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("dashboard.transport")

            statusRow

            if connection.showHostField {
                HStack(spacing: 10) {
                    Image(systemName: "network")
                        .foregroundStyle(Theme.creamDim)
                    TextField("raspberrypi.local:8080", text: hostBinding)
                        .textFieldStyle(.plain)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .onSubmit { send(DashboardEventHostSubmitted.shared) }
                        .accessibilityIdentifier(DashboardTestTags.shared.HOST_FIELD)
                }
                .font(.system(.callout, design: .monospaced))
                .padding(12)
                .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
            }

            clubSelector

            NavigationLink(value: AppRoute.calibration) {
                Label("Calibrate TI Radar", systemImage: "level.fill")
                    .font(.of(.callout, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.bordered)
            .tint(Theme.gold)
            .accessibilityIdentifier(DashboardTestTags.shared.CALIBRATE_RADAR)
        }
        .padding(16)
        .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
    }

    private var statusRow: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(statusColor)
                .frame(width: 10, height: 10)
                .shadow(color: statusColor.opacity(0.8), radius: 5)

            VStack(alignment: .leading, spacing: 2) {
                Text(connection.statusTitle)
                    .font(.of(.subheadline, weight: .semibold))
                Text(connection.state.description_)
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("dashboard.status")
            }

            Spacer()

            if connection.showRetry {
                Button("Retry") { send(DashboardEventRetry.shared) }
                    .buttonStyle(.bordered)
                    .tint(Theme.gold)
                    .accessibilityIdentifier(DashboardTestTags.shared.RETRY)
            } else if connection.showProgress {
                ProgressView()
                    .tint(Theme.gold)
                    .accessibilityIdentifier(DashboardTestTags.shared.PROGRESS)
            }
        }
    }

    private var statusColor: Color {
        switch connection.state {
        case is ConnectionStateConnected:
            Theme.success
        case is ConnectionStateScanning, is ConnectionStateConnecting, is ConnectionStateDiscovering:
            Theme.warning
        case is ConnectionStateError, is ConnectionStateUnavailable:
            Theme.danger
        default:
            Theme.neutral
        }
    }

    private var clubSelector: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CLUB FOR NEXT SHOT")
                .font(.ofEyebrow)
                .tracking(1.4)
                .foregroundStyle(Theme.creamDim)

            ClubSelectionMenu(
                selectedClub: connection.club,
                isEnabled: connection.clubMenuEnabled,
                onSelect: { send(DashboardEventClubSelected(club: $0)) }
            ) {
                HStack {
                    Image(systemName: "figure.golf")
                    Text(connection.club.displayName)
                        .font(.of(.body, weight: .semibold))
                    Spacer()
                    if connection.isChangingClub {
                        ProgressView().tint(Theme.gold)
                    } else {
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption)
                    }
                }
                .padding(12)
                .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(DashboardTestTags.shared.CLUB_SELECTOR)

            if let clubError = connection.clubError {
                Button {
                    send(DashboardEventDismissError.shared)
                } label: {
                    Label(clubError, systemImage: "exclamationmark.triangle.fill")
                        .font(.of(.caption))
                        .foregroundStyle(Theme.danger)
                        .multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(DashboardTestTags.shared.CLUB_ERROR)
                .accessibilityHint("Dismisses the error")
            }
        }
    }

    // MARK: Empty state

    private var emptyState: some View {
        ContentUnavailableView {
            Label("Waiting for a shot", systemImage: "dot.radiowaves.left.and.right")
        } description: {
            Text("Connect to your OpenFlight Pi, then hit a ball.")
        }
        .frame(minHeight: 300)
        .accessibilityIdentifier(DashboardTestTags.shared.EMPTY_STATE)
    }
}

/// The web UI's gold shot-flash (`App.css` `.shot-flash` / `@keyframes shot-impact`): a radial gold
/// burst that grows to 70% over the first half of 0.6 s while fading to 0.6, then fades out. It
/// replays whenever `trigger` changes and never flashes for the first render (`trigger == 0`).
/// Reduce Motion skips the growing burst and only fades a full-size glow.
struct ShotFlash: View {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?

    private static let duration: TimeInterval = 0.6

    var body: some View {
        TimelineView(.animation(paused: start == nil)) { context in
            Canvas { canvas, size in
                guard let start else { return }
                let p = min(1, context.date.timeIntervalSince(start) / Self.duration)
                guard p < 1 else { return }
                let alpha = p < 0.5 ? 1 - 0.4 * (p / 0.5) : 0.6 * (1 - (p - 0.5) / 0.5)
                let peak = max(size.width, size.height) * 0.7
                let radius = reduceMotion ? peak : peak * min(1, max(0.05, p / 0.5))
                canvas.fill(
                    Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 24),
                    with: .radialGradient(
                        Gradient(colors: [Theme.gold.opacity(alpha), .clear]),
                        center: CGPoint(x: size.width / 2, y: size.height / 2),
                        startRadius: 0,
                        endRadius: radius
                    )
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: trigger) { _, value in
            guard value > 0 else { return }
            let started = Date.now
            start = started
            Task {
                try? await Task.sleep(for: .seconds(Self.duration))
                if start == started { start = nil }
            }
        }
    }
}
