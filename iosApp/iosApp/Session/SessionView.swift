// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The session/stats screen (plans R5b/R6c), over the shared `SessionViewModel`: the source badge,
/// club tabs with the selected tab's stats, CSV export through `ShareLink`, Clear behind a
/// confirmation, and the shot list with native swipe-to-delete. Android's `SessionScreen.kt`
/// renders the same state.
struct SessionView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().sessionViewModel())
    @State private var export: CsvExport?
    @State private var message: String?

    var body: some View {
        SessionContent(state: host.state, send: host.send, export: export)
            .navigationTitle("Session")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink("History") { SessionHistoryView() }
                        .accessibilityIdentifier(SessionHistoryTestTags.shared.OPEN)
                }
            }
            .messageBanner($message)
            .task {
                await host.collect(host.viewModel.sideEffects) { effect in
                    if let csv = effect as? SessionEffectCsvReady {
                        export = CsvExport.write(csv: csv.csv, filename: csv.filename) ?? export
                    } else if let note = effect as? SessionEffectMessage {
                        message = note.text
                    }
                }
            }
            // The CSV is prepared ahead of time: `ShareLink` needs its file before the tap. It's
            // rebuilt whenever the shots change, so the export always matches the list.
            .onChange(of: exportKey(host.state), initial: true) { _, _ in
                host.send(SessionEventExportCsv.shared)
            }
    }

    private func exportKey(_ state: SessionUiState) -> [String] {
        [state.source.name] + state.shots.map(\.id)
    }
}

/// A CSV export written to a temporary `.csv` file, for `ShareLink`.
struct CsvExport: Equatable {
    let url: URL
    let filename: String

    static func write(csv: String, filename: String) -> CsvExport? {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            // Only the newest export is kept.
            for old in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
                try? FileManager.default.removeItem(at: old)
            }
            let url = directory.appendingPathComponent(filename)
            try csv.write(to: url, atomically: true, encoding: .utf8)
            return CsvExport(url: url, filename: filename)
        } catch {
            return nil
        }
    }
}

/// The stateless session screen.
struct SessionContent: View {
    let state: SessionUiState
    let send: (SessionEvent) -> Void
    let export: CsvExport?

    private var units: UnitSystem { state.units }
    private var isPi: Bool { state.source == .pi }
    /// Plan R8e: over Bluetooth (a read-and-select link) delete and clear are off, with a reason.
    /// Plan R8f: they're also off while a delete or clear is pending, so nothing is sent twice.
    private var canEdit: Bool { state.canEdit }

    var body: some View {
        List {
            Section { sourceBadge }
            if !(state.action is SessionActionStateIdle) && !(state.action is SessionActionStateConfirming) {
                Section {
                    SessionActionPanel(
                        state: state.action,
                        onRetry: { send(SessionEventRetryAction.shared) },
                        onDismiss: { send(SessionEventDismissAction.shared) }
                    )
                }
                .listRowBackground(Theme.bgElevated)
            }
            if let note = state.staleNote {
                Section {
                    Label(note, systemImage: "wifi.slash")
                        .font(.of(.subheadline))
                        .foregroundStyle(Theme.warning)
                        .accessibilityIdentifier(SessionActionTestTags.shared.STALE_NOTE)
                }
                .listRowBackground(Theme.bgCard)
            }
            if state.showSimulateShot {
                Section { simulateButton }.listRowBackground(Theme.bgCard)
            }
            if state.hasShots {
                if let dispersion = state.dispersion {
                    Section {
                        DispersionChartView(
                            dispersion: dispersion,
                            units: units,
                            selectedId: state.selectedShot?.id,
                            onSelect: { send(SessionEventSelectShot(id: $0)) }
                        )
                    }
                    .listRowBackground(Theme.bgCard)
                }
                Section { SessionClubTabs(state: state) { send(SessionEventSelectClub(club: $0)) } }
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                if let card = state.selectedShot {
                    Section {
                        SelectedShotCardView(
                            card: card,
                            units: units,
                            deletable: canEdit,
                            onClose: { send(SessionEventSelectShot(id: nil)) },
                            onDelete: { send(SessionEventDeleteShot(id: card.id)) }
                        )
                    }
                    .listRowBackground(Theme.bgElevated)
                }
                Section { SessionStatsGrid(state: state) }.listRowBackground(Theme.bgCard)
                Section { actions }.listRowBackground(Theme.bgCard)
                Section {
                    ForEach(state.shots, id: \.id) { row in
                        let selected = row.id == state.selectedShot?.id
                        SessionShotRowView(row: row, units: units)
                            .contentShape(Rectangle())
                            .onTapGesture { send(SessionEventSelectShot(id: row.id)) }
                            .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
                            .accessibilityHint("Shows this shot on the chart")
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if canEdit {
                                    // Not `.destructive`: the row stays until the delete is
                                    // confirmed (plan R8f), so it must not animate away here.
                                    Button {
                                        send(SessionEventDeleteShot(id: row.id))
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .tint(.red)
                                }
                            }
                            .listRowBackground(selected ? Theme.gold.opacity(0.14) : Theme.bgCard)
                    }
                } header: {
                    Text(canEdit ? "SHOTS · swipe left to delete" : "SHOTS")
                        .font(.ofEyebrow)
                        .tracking(1.4)
                        .foregroundStyle(Theme.gold)
                }
            } else {
                Section {
                    ContentUnavailableView {
                        Label("No shots recorded yet", systemImage: "list.bullet.rectangle")
                    } description: {
                        Text("Hit a ball and it shows up here with per-club stats.")
                    }
                    .accessibilityIdentifier("session.empty")
                }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .screenBackground()
        .sessionActionDialog(
            state.action,
            onConfirm: { send(SessionEventConfirmAction.shared) },
            onCancel: { send(SessionEventCancelAction.shared) }
        )
    }

    // MARK: Source

    private var sourceBadge: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(isPi ? Theme.success : Theme.neutral)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(isPi ? "Pi session" : "This phone")
                    .font(.of(.headline, weight: .semibold))
                Text(sourceDetail)
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("session.source")
        .listRowBackground((isPi ? Theme.success : Theme.cream).opacity(0.08))
    }

    private var sourceDetail: String {
        guard isPi else { return "Shots this phone received" }
        if let name = state.profileName { return "\(name)'s shots and stats from the OpenFlight Pi" }
        return "Shots and stats from the OpenFlight Pi"
    }

    private var simulateButton: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                send(SessionEventSimulateShot.shared)
            } label: {
                Label(state.simulateLabel, systemImage: "sparkles")
                    .font(.of(.body, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.gold)
            .foregroundStyle(Theme.bgDeep)
            .disabled(!state.simulateAvailability.isAvailable)
            .accessibilityIdentifier("session.simulate")
            if let reason = state.simulateAvailability.disabledReason {
                DisabledReason(reason: reason)
            }
        }
    }

    // MARK: Actions

    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            actionButtons
            if let reason = state.editAvailability.disabledReason {
                DisabledReason(reason: reason)
                    .accessibilityIdentifier("session.edit.disabledReason")
            }
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            Group {
                if let export {
                    ShareLink(item: export.url, preview: SharePreview(export.filename)) {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                } else {
                    Button {} label: {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(true)
                }
            }
            .accessibilityIdentifier("session.export")

            // The ViewModel asks for confirmation (plan R8f), shown by `sessionActionDialog`.
            Button(role: .destructive) {
                send(SessionEventClearHistory.shared)
            } label: {
                Label("Clear", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .tint(Theme.danger)
            .disabled(!canEdit)
            .accessibilityIdentifier("session.clear")
        }
        .font(.of(.body, weight: .semibold))
        .buttonStyle(.bordered)
        .tint(Theme.gold)
        .controlSize(.large)
    }
}

/// "All" plus one chip per club (`StatsView.tsx`'s club filter), shared by the live session and a
/// stored one (plan R8h).
struct SessionClubTabs: View {
    let state: SessionUiState
    let onSelect: (String?) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ChipButton(
                    label: "All",
                    count: state.allCount,
                    isSelected: state.selectedClub == nil,
                    minTouchTarget: true
                ) {
                    onSelect(nil)
                }
                .accessibilityIdentifier("session.tab.all")
                ForEach(state.clubChips, id: \.club) { chip in
                    ChipButton(
                        label: Units.clubLabel(chip.club),
                        count: chip.count,
                        isSelected: state.selectedClub == chip.club,
                        minTouchTarget: true
                    ) {
                        onSelect(chip.club)
                    }
                    .accessibilityIdentifier("session.tab.\(chip.club)")
                }
            }
            .padding(.horizontal, 2)
        }
        .listRowBackground(Color.clear)
    }
}

/// The selected tab's stats tiles (the shared `statTiles`: the kiosk's six plus minimum ball speed
/// and its standard deviation), or swing-speed tiles for a swing session. Two columns at the
/// accessibility text sizes, so the labels aren't squeezed.
struct SessionStatsGrid: View {
    let state: SessionUiState
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let columns = dynamicTypeSize.isAccessibilitySize ? 2 : 3
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: columns), spacing: 10) {
            ForEach(state.statTiles, id: \.label) { tile in
                VStack(alignment: .leading, spacing: 4) {
                    Text(tile.value)
                        .font(.ofDisplay(.title2))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text(tile.label)
                        .font(.of(.caption, weight: .semibold))
                        .foregroundStyle(Theme.creamDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
                .padding(10)
                .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityElement(children: .ignore)
                // "Average ball speed", "151.4 mph": spelled out, with the unit.
                .accessibilityLabel(tile.spokenLabel)
                .accessibilityValue(tile.spokenValue)
                .accessibilityIdentifier("session.stat.\(tile.label)")
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session.stats")
    }
}

/// One shot (`ShotList.tsx`'s `ShotRow`): number, club or implement, time and player, then ball
/// speed and carry, or the swing speed for a training rep.
struct SessionShotRowView: View {
    let row: SessionShotRow
    let units: UnitSystem

    var body: some View {
        HStack(spacing: 12) {
            Text("#\(row.shotNumber)")
                .font(.of(.subheadline, weight: .bold).monospacedDigit())
                .foregroundStyle(Theme.gold)
                .frame(minWidth: 30, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                // Two lines, so large text wraps instead of cutting the club or the time off.
                Text(row.implementLabel ?? Units.clubLabel(row.club))
                    .font(.of(.headline, weight: .semibold))
                    .lineLimit(2)
                Text([Units.clockTime(row.timestamp), row.profileName].compactMap { $0 }.joined(separator: " · "))
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if row.isSwingSpeed {
                metric(Units.speed(row.swingSpeedMph, units), Units.speedUnit(units).uppercased())
            } else {
                metric(Units.speed(row.ballSpeedMph, units), Units.speedUnit(units).uppercased())
                metric(Units.distance(row.carryYards, units), Units.distanceUnit(units).uppercased())
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("session.shot.\(row.id)")
    }

    private func metric(_ value: String, _ unit: String) -> some View {
        VStack(alignment: .trailing, spacing: 2) {
            Text(value)
                .font(.of(.title3, weight: .bold).monospacedDigit())
                .lineLimit(1)
            Text(unit)
                .font(.of(.caption2, weight: .semibold))
                .foregroundStyle(Theme.creamDim)
        }
        .frame(minWidth: 58, alignment: .trailing)
    }
}
