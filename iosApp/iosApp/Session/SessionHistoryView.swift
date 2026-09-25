// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The stored session list (plan R8h), over the shared `SessionHistoryViewModel`: date, shot count
/// and first/last shot time per session, newest first, and "Clear all history" behind a
/// confirmation. Android's `SessionHistoryScreen.kt` renders the same state.
struct SessionHistoryView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().sessionHistoryViewModel())

    var body: some View {
        SessionHistoryContent(state: host.state, send: host.send)
            .navigationTitle("History")
    }
}

/// The stateless history list.
struct SessionHistoryContent: View {
    let state: SessionHistoryUiState
    let send: (SessionHistoryEvent) -> Void

    @State private var confirmingClear = false

    var body: some View {
        List {
            if !state.isPersistent {
                Section {
                    Text("History can't be saved on this device right now; these sessions last until the app closes.")
                        .font(.of(.subheadline))
                        .foregroundStyle(Theme.warning)
                        .accessibilityIdentifier(SessionHistoryTestTags.shared.NOT_PERSISTENT)
                }
                .listRowBackground(Theme.bgCard)
            }
            if state.loaded && state.sessions.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label("No sessions yet", systemImage: "clock.arrow.circlepath")
                    } description: {
                        Text("Every connection to the Pi starts a session; its shots are kept here.")
                    }
                    .accessibilityIdentifier(SessionHistoryTestTags.shared.EMPTY)
                }
            }
            if !state.sessions.isEmpty {
                Section {
                    ForEach(state.sessions, id: \.id) { session in
                        NavigationLink {
                            SessionHistoryDetailView(sessionId: session.id)
                        } label: {
                            SessionHistoryRowView(session: session)
                        }
                        .accessibilityIdentifier(SessionHistoryTestTags.shared.session(id: session.id))
                    }
                    .listRowBackground(Theme.bgCard)
                }
                Section {
                    Button(role: .destructive) {
                        confirmingClear = true
                    } label: {
                        Label("Clear all history", systemImage: "trash")
                            .frame(maxWidth: .infinity)
                    }
                    .tint(Theme.danger)
                    .accessibilityIdentifier(SessionHistoryTestTags.shared.CLEAR_ALL)
                    .confirmationDialog("Clear all history?", isPresented: $confirmingClear, titleVisibility: .visible) {
                        Button("Clear all", role: .destructive) { send(SessionHistoryEventClearAll.shared) }
                            .accessibilityIdentifier(SessionHistoryTestTags.shared.CLEAR_ALL_CONFIRM)
                        Button("Cancel", role: .cancel) {}
                            .accessibilityIdentifier(SessionHistoryTestTags.shared.CLEAR_ALL_CANCEL)
                    } message: {
                        Text("Every stored session is deleted from this phone. The Pi's session is not affected.")
                    }
                }
                .listRowBackground(Theme.bgCard)
            }
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(SessionHistoryTestTags.shared.LIST)
        .screenBackground()
    }
}

/// One stored session: its date and time range, and how many shots it holds.
struct SessionHistoryRowView: View {
    let session: SessionHistoryRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(session.date)
                    .font(.of(.headline, weight: .semibold))
                Text([session.timeRange, session.transportLabel].compactMap { $0 }.joined(separator: " · "))
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 2) {
                Text(session.shotCountLabel)
                    .font(.of(.subheadline, weight: .bold).monospacedDigit())
                    .foregroundStyle(Theme.gold)
                if session.isCurrent {
                    Text("Current")
                        .font(.of(.caption2, weight: .semibold))
                        .foregroundStyle(Theme.creamDim)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// One stored session (plan R8h): the live Session screen's club tabs, stats and shot rows over the
/// stored shots, with a CSV export through `ShareLink`.
struct SessionHistoryDetailView: View {
    @StateObject private var host: ViewModelHost<SessionHistoryDetailViewModel>
    @State private var export: CsvExport?

    init(sessionId: String) {
        _host = StateObject(wrappedValue: ViewModelHost(KoinHelper().sessionHistoryDetailViewModel(sessionId: sessionId)))
    }

    var body: some View {
        SessionHistoryDetailContent(state: host.state, send: host.send, export: export)
            .navigationTitle(host.state.title.isEmpty ? "Session" : host.state.title)
            .task {
                await host.collect(host.viewModel.sideEffects) { effect in
                    if let csv = effect as? SessionEffectCsvReady {
                        export = CsvExport.write(csv: csv.csv, filename: csv.filename) ?? export
                    }
                }
            }
            // `ShareLink` needs its file before the tap, so the CSV follows the stored shots.
            .onChange(of: host.state.session.shots.map(\.id), initial: true) { _, _ in
                host.send(SessionHistoryDetailEventExportCsv.shared)
            }
    }
}

/// The stateless stored-session detail.
struct SessionHistoryDetailContent: View {
    let state: SessionHistoryDetailUiState
    let send: (SessionHistoryDetailEvent) -> Void
    let export: CsvExport?

    private var session: SessionUiState { state.session }

    var body: some View {
        List {
            Section {
                Text(state.subtitle)
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier(SessionHistoryTestTags.shared.DETAIL_TITLE)
            }
            .listRowBackground(Color.clear)
            if session.hasShots {
                Section { SessionClubTabs(state: session) { send(SessionHistoryDetailEventSelectClub(club: $0)) } }
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                Section { SessionStatsGrid(state: session) }.listRowBackground(Theme.bgCard)
                Section { exportButton }.listRowBackground(Theme.bgCard)
                Section {
                    ForEach(session.shots, id: \.id) { row in
                        SessionShotRowView(row: row, units: session.units)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    send(SessionHistoryDetailEventDeleteShot(id: row.id))
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                    }
                    .listRowBackground(Theme.bgCard)
                } header: {
                    Text("SHOTS · swipe left to delete")
                        .font(.ofEyebrow)
                        .tracking(1.4)
                        .foregroundStyle(Theme.gold)
                }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .screenBackground()
    }

    private var exportButton: some View {
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
        .font(.of(.body, weight: .semibold))
        .buttonStyle(.bordered)
        .tint(Theme.gold)
        .accessibilityIdentifier(SessionHistoryTestTags.shared.DETAIL_EXPORT)
    }
}
