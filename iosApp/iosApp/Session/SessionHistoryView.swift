// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The stored session list (plan R8h), over the shared `SessionHistoryViewModel`: day, time range,
/// shot count and how each session was recorded, newest first, with the current one badged, and
/// "Clear all history" behind a confirmation with its progress shown (plan R8f). Android's
/// `SessionHistoryScreen.kt` renders the same state.
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

    var body: some View {
        List {
            if !(state.action is SessionActionStateIdle) && !(state.action is SessionActionStateConfirming) {
                Section {
                    SessionActionPanel(
                        state: state.action,
                        onRetry: { send(SessionHistoryEventRetryAction.shared) },
                        onDismiss: { send(SessionHistoryEventDismissAction.shared) }
                    )
                }
                .listRowBackground(Theme.bgElevated)
            }
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
                        send(SessionHistoryEventClearAll.shared)
                    } label: {
                        Label("Clear all history", systemImage: "trash")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .tint(Theme.danger)
                    .disabled(state.action.isBusy)
                    .accessibilityIdentifier(SessionHistoryTestTags.shared.CLEAR_ALL)
                }
                .listRowBackground(Theme.bgCard)
            }
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(SessionHistoryTestTags.shared.LIST)
        .screenBackground()
        .reducingMotion()
        .sessionActionDialog(
            state.action,
            onConfirm: { send(SessionHistoryEventConfirmAction.shared) },
            onCancel: { send(SessionHistoryEventCancelAction.shared) }
        )
    }
}

/// "Current": a labelled pill, the same on the list and the detail (and on Android).
struct CurrentBadge: View {
    var body: some View {
        StatusPill(text: SessionHistoryRow.companion.CURRENT_LABEL, color: Theme.success)
            .accessibilityIdentifier(SessionHistoryTestTags.shared.CURRENT)
    }
}

/// One stored session: its day, time range and how it was recorded, and how many shots it holds.
/// VoiceOver reads it as one sentence (`accessibilityLabel`, e.g. "Thursday 25 September 2026,
/// 10:03 to 10:45, 24 shots, Wi-Fi, current session").
struct SessionHistoryRowView: View {
    let session: SessionHistoryRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(session.date)
                    .font(.of(.headline, weight: .semibold))
                Text(session.detailLine)
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 4) {
                Text(session.shotCountLabel)
                    .font(.of(.subheadline, weight: .bold).monospacedDigit())
                    .foregroundStyle(Theme.gold)
                if session.isCurrent {
                    CurrentBadge()
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(session.accessibilityLabel)
    }
}

/// One stored session (plan R8h): the live Session screen's club tabs, stats and shot rows over the
/// stored shots, a profile filter when more than one profile hit, and a CSV export through
/// `ShareLink`. Deleting a shot asks first and shows its progress (plan R8f).
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
            Section { heading }
                .listRowBackground(Color.clear)
            if !(state.action is SessionActionStateIdle) && !(state.action is SessionActionStateConfirming) {
                Section {
                    SessionActionPanel(
                        state: state.action,
                        onRetry: { send(SessionHistoryDetailEventRetryAction.shared) },
                        onDismiss: { send(SessionHistoryDetailEventDismissAction.shared) }
                    )
                }
                .listRowBackground(Theme.bgElevated)
            }
            if !state.profileChips.isEmpty {
                Section { profileChips }
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            if session.hasShots {
                Section { SessionClubTabs(state: session) { send(SessionHistoryDetailEventSelectClub(club: $0)) } }
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                Section { SessionStatsGrid(state: session) }.listRowBackground(Theme.bgCard)
                Section { exportButton }.listRowBackground(Theme.bgCard)
                Section {
                    ForEach(session.shots, id: \.id) { row in
                        SessionShotRowView(row: row, units: session.units)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if state.canDelete {
                                    // Not `.destructive`: the row stays until the delete is confirmed.
                                    Button {
                                        send(SessionHistoryDetailEventDeleteShot(id: row.id))
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .tint(.red)
                                }
                            }
                    }
                    .listRowBackground(Theme.bgCard)
                } header: {
                    Text(state.canDelete ? "SHOTS · swipe left to delete" : "SHOTS")
                        .font(.ofEyebrow)
                        .tracking(1.4)
                        .foregroundStyle(Theme.gold)
                }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .screenBackground()
        .reducingMotion()
        .sessionActionDialog(
            state.action,
            onConfirm: { send(SessionHistoryDetailEventConfirmAction.shared) },
            onCancel: { send(SessionHistoryDetailEventCancelAction.shared) }
        )
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(state.subtitle)
                .font(.of(.subheadline))
                .foregroundStyle(Theme.creamDim)
                .accessibilityIdentifier(SessionHistoryTestTags.shared.DETAIL_TITLE)
            HStack(spacing: 8) {
                if let source = state.sourceLine {
                    Text(source)
                        .font(.of(.caption))
                        .foregroundStyle(Theme.creamDim)
                        .accessibilityIdentifier(SessionHistoryTestTags.shared.DETAIL_SOURCE)
                }
                if state.isCurrent {
                    CurrentBadge()
                }
            }
        }
    }

    /// "All" plus one chip per profile, with its shot count.
    private var profileChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ChipButton(
                    label: "All profiles",
                    isSelected: state.selectedProfileId == nil,
                    minTouchTarget: true
                ) {
                    send(SessionHistoryDetailEventSelectProfile(profileId: nil))
                }
                .accessibilityIdentifier(SessionHistoryTestTags.shared.PROFILE_ALL)
                ForEach(state.profileChips, id: \.id) { chip in
                    ChipButton(
                        label: chip.name,
                        count: chip.count,
                        isSelected: state.selectedProfileId == chip.id,
                        minTouchTarget: true
                    ) {
                        send(SessionHistoryDetailEventSelectProfile(profileId: chip.id))
                    }
                    .accessibilityIdentifier(SessionHistoryTestTags.shared.profile(id: chip.id))
                }
            }
            .padding(.horizontal, 2)
        }
        .listRowBackground(Color.clear)
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
        .controlSize(.large)
        .tint(Theme.gold)
        .accessibilityIdentifier(SessionHistoryTestTags.shared.DETAIL_EXPORT)
    }
}
