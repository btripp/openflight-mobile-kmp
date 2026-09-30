// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// My Bag (plan F5) over the shared `BagViewModel`: the conditions card, the active bag's clubs
/// with their average carry and the gap to the next club, editing (reorder, add, remove, make and
/// model), and the selected club's detail. On a regular-width window (iPad) the bag list and the
/// club detail sit side by side; on compact width the detail is pushed. Android's `BagScreen.kt`
/// renders the same state.
struct BagView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().bagViewModel())
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var selectedClub: String?
    @State private var editingConditions = false

    var body: some View {
        Group {
            if sizeClass == .regular {
                HStack(spacing: 0) {
                    BagContent(
                        state: host.state,
                        send: host.send,
                        selectedClub: $selectedClub,
                        editingConditions: $editingConditions,
                        pushesDetail: false
                    )
                    .frame(maxWidth: 420)
                    Divider().background(Theme.bgElevated)
                    Group {
                        if let wire = selectedClub {
                            ClubDetailView(wireValue: wire).id(wire)
                        } else {
                            Text("Pick a club to see its distances.")
                                .foregroundStyle(Theme.creamDim)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .accessibilityIdentifier(BagTestTags.shared.DETAIL_PLACEHOLDER)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .screenBackground()
                }
            } else {
                BagContent(
                    state: host.state,
                    send: host.send,
                    selectedClub: $selectedClub,
                    editingConditions: $editingConditions,
                    pushesDetail: true
                )
            }
        }
        .navigationTitle("My Bag")
        .task {
            await host.collect(host.viewModel.sideEffects) { effect in
                if effect is BagEffectConditionsSaved { editingConditions = false }
            }
        }
    }
}

/// The stateless bag list.
struct BagContent: View {
    let state: BagUiState
    let send: (BagEvent) -> Void
    @Binding var selectedClub: String?
    @Binding var editingConditions: Bool
    /// Compact width: a club opens its detail as a pushed screen.
    let pushesDetail: Bool
    @State private var editMode: EditMode = .inactive
    @State private var editingClub: BagClubRow?

    var body: some View {
        List {
            Section {
                ConditionsCardView(card: state.conditions) { editingConditions = true }
            }
            .listRowBackground(Theme.bgCard)

            if let error = state.error {
                Section {
                    HStack {
                        Text(error).foregroundStyle(Theme.danger).font(.of(.subheadline))
                            .accessibilityIdentifier(BagTestTags.shared.ERROR)
                        Spacer()
                        Button("Dismiss") { send(BagEventDismissError.shared) }
                    }
                }
                .listRowBackground(Theme.bgCard)
            }

            Section {
                if state.bags.count > 1 {
                    Picker("Bag", selection: Binding(
                        get: { state.bagId ?? "" },
                        set: { send(BagEventSelectBag(bagId: $0)) }
                    )) {
                        ForEach(state.bags, id: \.id) { bag in
                            Text(bag.name).tag(bag.id).accessibilityIdentifier(BagTestTags.shared.bag(bagId: bag.id))
                        }
                    }
                }
                NavigationLink {
                    ClubAnalysisView()
                } label: {
                    Label("Club analysis", systemImage: "chart.bar.xaxis")
                }
                .accessibilityIdentifier(BagTestTags.shared.OPEN_ANALYSIS)
                if state.carryAdjusted {
                    Text("Carries adjusted for conditions")
                        .font(.of(.caption))
                        .foregroundStyle(Theme.creamMuted)
                }
            }
            .listRowBackground(Theme.bgCard)

            Section {
                ForEach(state.clubs, id: \.id) { row in
                    clubRow(row)
                }
                .onMove { from, to in
                    var ids = state.clubs.map(\.id)
                    ids.move(fromOffsets: from, toOffset: to)
                    send(BagEventReorder(clubIds: ids))
                }
                .onDelete { offsets in
                    offsets.map { state.clubs[$0].id }.forEach { send(BagEventRemoveClub(clubId: $0)) }
                }
                if editMode == .active && !state.addableClubs.isEmpty {
                    Menu {
                        ForEach(state.addableClubs, id: \.self) { club in
                            Button(club.displayName) { send(BagEventAddClub(club: club)) }
                                .accessibilityIdentifier(BagTestTags.shared.add(wireValue: club.wireValue))
                        }
                    } label: {
                        Label("Add a club", systemImage: "plus.circle")
                    }
                    .accessibilityIdentifier(BagTestTags.shared.ADD_CLUB)
                }
            } header: {
                Text(state.bagName)
                    .font(.of(.headline, weight: .semibold))
                    .foregroundStyle(Theme.cream)
                    .accessibilityIdentifier(BagTestTags.shared.BAG_NAME)
            } footer: {
                Text(BagCopy.shared.EXCLUDES_IMPORTED).font(.of(.caption)).foregroundStyle(Theme.creamMuted)
            }
            .listRowBackground(Theme.bgCard)
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(BagTestTags.shared.LIST)
        .environment(\.editMode, $editMode)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(editMode == .active ? "Finish" : "Edit") {
                    withAnimation { editMode = editMode == .active ? .inactive : .active }
                }
                .accessibilityIdentifier(BagTestTags.shared.EDIT_TOGGLE)
            }
        }
        .screenBackground()
        .sheet(isPresented: $editingConditions) {
            ConditionsEditorView(card: state.conditions) { send(BagEventSaveConditions(form: $0)) }
        }
        .sheet(item: Binding(get: { editingClub.map(IdentifiedRow.init) }, set: { editingClub = $0?.row })) { item in
            ClubEditorView(row: item.row) { make, model, loft in
                send(BagEventEditClub(clubId: item.row.id, make: make, model: model, loft: loft))
                editingClub = nil
            }
        }
    }

    @ViewBuilder
    private func clubRow(_ row: BagClubRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if pushesDetail {
                NavigationLink {
                    ClubDetailView(wireValue: row.wireValue)
                } label: {
                    BagClubRowView(row: row)
                }
            } else {
                Button { selectedClub = row.wireValue } label: { BagClubRowView(row: row) }
                    .buttonStyle(.plain)
                    .listRowBackground(selectedClub == row.wireValue ? Theme.bgElevated : Theme.bgCard)
            }
            if let gap = row.gap {
                StatusPill(text: gap.label, color: gap.flag == nil ? Theme.neutral : Theme.warning)
                    .padding(.leading, 52)
                    .accessibilityIdentifier(BagTestTags.shared.gap(wireValue: row.wireValue))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(BagTestTags.shared.club(wireValue: row.wireValue))
        .swipeActions(edge: .leading) {
            Button("Details") { editingClub = row }.tint(Theme.info)
        }
        .contextMenu {
            Button("Make, model and loft") { editingClub = row }
        }
    }
}

/// Wraps a row for `.sheet(item:)`, which needs `Identifiable`.
private struct IdentifiedRow: Identifiable {
    let row: BagClubRow
    var id: String { row.id }
}

/// One club: its badge, name, make and model, average carry and spread. VoiceOver reads it as one
/// sentence (`accessibilityLabel`).
struct BagClubRowView: View {
    let row: BagClubRow

    var body: some View {
        HStack(spacing: 12) {
            ClubBadge(shortLabel: row.shortLabel, colorIndex: Int(row.colorIndex))
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).font(.of(.headline, weight: .semibold))
                Text(row.makeModel ?? "Add make and model")
                    .font(.of(.caption))
                    .foregroundStyle(row.makeModel == nil ? Theme.creamMuted : Theme.creamDim)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(row.carryLabel)
                    .font(.of(.headline, weight: .bold).monospacedDigit())
                    .foregroundStyle(Theme.gold)
                Text([row.plusMinusLabel, row.shotCountLabel].compactMap { $0 }.joined(separator: " · "))
                    .font(.of(.caption2))
                    .foregroundStyle(Theme.creamMuted)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.accessibilityLabel)
    }
}

/// A club's short label ("7i") in its chart colour.
struct ClubBadge: View {
    let shortLabel: String
    let colorIndex: Int

    var body: some View {
        Text(shortLabel)
            .font(.of(.caption, weight: .bold))
            .foregroundStyle(Theme.bgDeep)
            .frame(width: 40, height: 40)
            .background(Theme.clubColor(colorIndex), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// The conditions card: mode, summary, and the wind note when wind needs a target direction.
struct ConditionsCardView: View {
    let card: BagConditionsCardState
    let onEdit: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // Issue #81: at an accessibility text size the pill and Edit go under the title.
            AdaptiveHeaderRow {
                Text("Conditions").font(.of(.headline, weight: .semibold))
            } actions: {
                StatusPill(text: card.modeLabel, color: Theme.info)
                Button("Edit", action: onEdit)
                    .accessibilityIdentifier(BagTestTags.shared.CONDITIONS_EDIT)
            }
            Text(card.summary)
                .font(.of(.subheadline))
                .foregroundStyle(Theme.creamDim)
                .accessibilityIdentifier(BagTestTags.shared.CONDITIONS_SUMMARY)
            if let note = card.windNote {
                Text(note)
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.warning)
                    .accessibilityIdentifier(BagTestTags.shared.WIND_NOTE)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(BagTestTags.shared.CONDITIONS_CARD)
    }
}

/// The manual conditions editor. The shared `ConditionsForm` parses and validates the fields.
struct ConditionsEditorView: View {
    let card: BagConditionsCardState
    let onSave: (ConditionsForm) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var altitude = ""
    @State private var temperature = ""
    @State private var windSpeed = ""
    @State private var windFrom = ""
    @State private var target = ""
    @State private var surface: Firmness = .normal

    var body: some View {
        NavigationStack {
            Form {
                field("Altitude (\(card.altitudeUnit))", $altitude, BagTestTags.shared.CONDITIONS_ALTITUDE)
                field("Temperature (\(card.temperatureUnit))", $temperature, BagTestTags.shared.CONDITIONS_TEMPERATURE)
                field("Wind speed (\(card.windUnit))", $windSpeed, BagTestTags.shared.CONDITIONS_WIND_SPEED)
                field("Wind from (degrees, 0 = north)", $windFrom, BagTestTags.shared.CONDITIONS_WIND_FROM)
                field("Target direction (degrees; blank = not set)", $target, BagTestTags.shared.CONDITIONS_TARGET)
                Picker("Landing area", selection: $surface) {
                    ForEach(Firmness.entries, id: \.self) { firmness in
                        Text(BagCopy.shared.surfaceOption(firmness: firmness)).tag(firmness)
                    }
                }
                .pickerStyle(.segmented)
                if let error = card.formError {
                    Text(error).foregroundStyle(Theme.danger)
                        .accessibilityIdentifier(BagTestTags.shared.CONDITIONS_ERROR)
                }
            }
            .navigationTitle("Conditions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(ConditionsForm(
                            altitude: altitude,
                            temperature: temperature,
                            windSpeed: windSpeed,
                            windFrom: windFrom,
                            surface: surface,
                            targetBearing: target
                        ))
                    }
                    .accessibilityIdentifier(BagTestTags.shared.CONDITIONS_SAVE)
                }
            }
        }
        .onAppear {
            altitude = card.form.altitude
            temperature = card.form.temperature
            windSpeed = card.form.windSpeed
            windFrom = card.form.windFrom
            target = card.form.targetBearing
            surface = card.form.surface
        }
    }

    private func field(_ label: String, _ text: Binding<String>, _ identifier: String) -> some View {
        LabeledContent(label) {
            TextField(label, text: text)
                .keyboardType(.numbersAndPunctuation)
                .multilineTextAlignment(.trailing)
                .accessibilityIdentifier(identifier)
        }
    }
}

/// Make, model and loft for one club.
struct ClubEditorView: View {
    let row: BagClubRow
    let onSave: (String, String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var make = ""
    @State private var model = ""
    @State private var loft = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Make", text: $make)
                TextField("Model", text: $model)
                TextField("Loft (degrees)", text: $loft).keyboardType(.decimalPad)
            }
            .navigationTitle(row.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { onSave(make, model, loft) } }
            }
        }
        .onAppear {
            make = row.make ?? ""
            model = row.model ?? ""
            loft = row.loftDeg.map { "\($0.doubleValue)" } ?? ""
        }
    }
}
