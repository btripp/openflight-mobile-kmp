// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Plan R8f: the profile picker beside the club (Expo `ProfilePicker.tsx`, `ProfileNameForm.tsx`),
/// over the shared `ProfilePickerState`. Shows what the Pi last reported, never the last tap.
/// Android's `ProfilePickerUi.kt` renders the same state.
struct ProfileSelector: View {
    let profile: ProfilePickerState
    let send: (DashboardEvent) -> Void

    private var sheetBinding: Binding<Bool> {
        Binding(
            get: { !(profile.sheet is ProfileSheetClosed) },
            set: { presented in
                if !presented { send(ProfilePickerEventClose.shared) }
            }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("PROFILE")
                .font(.ofEyebrow)
                .tracking(1.4)
                .foregroundStyle(Theme.creamDim)

            Button {
                send(ProfilePickerEventOpen.shared)
            } label: {
                HStack {
                    Image(systemName: "person.crop.circle")
                    Text(profile.label)
                        .font(.of(.body, weight: .semibold))
                        .lineLimit(2)
                    Spacer()
                    Text("Change")
                        .font(.of(.subheadline, weight: .semibold))
                        .foregroundStyle(Theme.gold)
                }
                .padding(12)
                .frame(minHeight: 44)
                .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!profile.selection.isAvailable)
            .accessibilityLabel("Profile: \(profile.label). Change profile")
            .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_BUTTON)

            if let reason = profile.selection.disabledReason {
                DisabledReason(reason: reason)
            }
            if profile.sheet is ProfileSheetClosed, let notice = profile.notice {
                ProfileNoticeRow(text: notice, send: send)
            }
        }
        .sheet(isPresented: sheetBinding) {
            ProfileSheetView(profile: profile, send: send)
        }
    }
}

private struct ProfileNoticeRow: View {
    let text: String
    let send: (DashboardEvent) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            NoticeRow(title: text, tone: .warning)
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_NOTICE)
            Button("OK") { send(ProfilePickerEventDismissNotice.shared) }
                .frame(minHeight: 44)
        }
    }
}

private struct ProfileSheetView: View {
    let profile: ProfilePickerState
    let send: (DashboardEvent) -> Void

    var body: some View {
        NavigationStack {
            List {
                if let notice = profile.notice {
                    ProfileNoticeRow(text: notice, send: send)
                        .listRowBackground(Theme.bgCard)
                }
                content
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(ProfilePickerState.companion.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { send(ProfilePickerEventClose.shared) }
                        .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_DONE)
                }
                if profile.sheet is ProfileSheetList {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Add") { send(ProfilePickerEventStartAdd.shared) }
                            .disabled(!profile.addEnabled)
                            .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_ADD)
                    }
                }
            }
        }
        .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_SHEET)
        .presentationDetents([.large])
    }

    @ViewBuilder
    private var content: some View {
        switch profile.sheet {
        case let adding as ProfileSheetAdding:
            ProfileNameForm(title: "Add a profile", confirmLabel: "Add", draft: adding.draft, error: adding.error, send: send)
        case let renaming as ProfileSheetRenaming:
            ProfileNameForm(
                title: "Rename profile",
                confirmLabel: "Save",
                draft: renaming.draft,
                error: renaming.error,
                send: send
            )
        case let removal as ProfileSheetConfirmingRemoval:
            removalConfirmation(removal)
        default:
            roster
        }
    }

    @ViewBuilder
    private var roster: some View {
        if !profile.loaded {
            // The Pi never reports an empty roster: "nothing yet" is a wait, not an empty state.
            ProgressView()
                .accessibilityLabel("Loading profiles")
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_LOADING)
                .listRowBackground(Theme.bgCard)
        } else {
            Section {
                ForEach(profile.rows, id: \.id) { row in
                    ProfileRowView(row: row, editsAvailable: profile.edits.isAvailable, send: send)
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    if !profile.canAdd {
                        Text(ProfilePickerState.companion.AT_CAPACITY_TEXT)
                    }
                    if let reason = profile.edits.disabledReason {
                        DisabledReason(reason: reason)
                    }
                }
                .font(.of(.footnote))
                .foregroundStyle(Theme.creamDim)
            }
            .listRowBackground(Theme.bgCard)
        }
    }

    private func removalConfirmation(_ removal: ProfileSheetConfirmingRemoval) -> some View {
        Section {
            Text("The Pi deletes this profile for every screen and phone connected to it.")
                .font(.of(.subheadline))
                .foregroundStyle(Theme.creamDim)
            Button("Remove", role: .destructive) { send(ProfilePickerEventConfirmRemove.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_REMOVE_CONFIRM)
            Button("Cancel") { send(ProfilePickerEventCancelForm.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_CANCEL)
        } header: {
            Text("Remove “\(removal.name)”?")
                .font(.of(.headline))
                .foregroundStyle(Theme.cream)
                .accessibilityAddTraits(.isHeader)
        }
        .listRowBackground(Theme.bgCard)
    }
}

private struct ProfileRowView: View {
    let row: ProfileRow
    let editsAvailable: Bool
    let send: (DashboardEvent) -> Void

    var body: some View {
        Button {
            send(ProfilePickerEventSelect(profileId: row.id))
        } label: {
            HStack {
                Text(row.name)
                    .font(.of(.body, weight: row.active ? .semibold : .regular))
                    .foregroundStyle(row.active ? Theme.gold : Theme.cream)
                Spacer()
                if row.active {
                    // A check mark, not only the gold text, marks the active profile.
                    Image(systemName: "checkmark")
                        .foregroundStyle(Theme.gold)
                        .accessibilityHidden(true)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(row.active ? .isSelected : [])
        .accessibilityIdentifier(DashboardTestTags.shared.profileRow(id: row.id))
        .swipeActions(edge: .trailing) {
            if editsAvailable {
                if row.removeBlockedReason == nil {
                    Button("Remove", role: .destructive) { send(ProfilePickerEventRemove(profileId: row.id)) }
                }
                Button("Rename") { send(ProfilePickerEventStartRename(profileId: row.id)) }
                    .tint(Theme.gold)
            }
        }
        .contextMenu {
            if editsAvailable {
                Button("Rename") { send(ProfilePickerEventStartRename(profileId: row.id)) }
                    .accessibilityIdentifier(DashboardTestTags.shared.profileRename(id: row.id))
                Button("Remove", role: .destructive) { send(ProfilePickerEventRemove(profileId: row.id)) }
                    .disabled(row.removeBlockedReason != nil)
                    .accessibilityIdentifier(DashboardTestTags.shared.profileRemove(id: row.id))
            }
        }
        .accessibilityActions {
            if editsAvailable {
                Button("Rename") { send(ProfilePickerEventStartRename(profileId: row.id)) }
                if row.removeBlockedReason == nil {
                    Button("Remove") { send(ProfilePickerEventRemove(profileId: row.id)) }
                }
            }
        }
    }
}

/// Rendered inside the sheet rather than as a second modal over it (Expo `ProfileNameForm.tsx`).
private struct ProfileNameForm: View {
    let title: String
    let confirmLabel: String
    let draft: String
    let error: String?
    let send: (DashboardEvent) -> Void

    @FocusState private var focused: Bool

    private var nameBinding: Binding<String> {
        Binding(get: { draft }, set: { send(ProfilePickerEventNameEdited(text: $0)) })
    }

    var body: some View {
        Section {
            TextField("Name", text: nameBinding)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($focused)
                .onSubmit { send(ProfilePickerEventSubmitName.shared) }
                .frame(minHeight: 44)
                .accessibilityLabel("Profile name")
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_NAME_FIELD)
            if let error {
                NoticeRow(title: error, tone: .problem)
                    .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_FORM_ERROR)
            }
            Button(confirmLabel) { send(ProfilePickerEventSubmitName.shared) }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                .frame(minHeight: 44)
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_SAVE)
            Button("Cancel") { send(ProfilePickerEventCancelForm.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier(DashboardTestTags.shared.PROFILE_CANCEL)
        } header: {
            Text(title)
                .font(.of(.headline))
                .foregroundStyle(Theme.cream)
                .accessibilityAddTraits(.isHeader)
        }
        .listRowBackground(Theme.bgCard)
        .onAppear { focused = true }
    }
}
