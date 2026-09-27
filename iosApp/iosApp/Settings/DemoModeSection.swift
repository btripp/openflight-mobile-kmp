// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Accessibility identifiers for Settings › Device › Demo mode (plan F14), the same strings as
/// Android's `SettingsTestTags`.
enum DemoSettingsTags {
    static let toggle = "settings.demo.switch"
    static let autoFire = "settings.demo.autoFire"
    static let clear = "settings.demo.clear"
    static let clearConfirm = "settings.demo.clear.confirm"
    static let clearCancel = "settings.demo.clear.cancel"
}

/// Plan F14: Settings › Device › Demo mode: the switch (on and off at once, no relaunch), how often
/// the pretend Pi hits a shot by itself while it's on, and "Clear demo data" behind a confirmation.
struct DemoModeSection: View {
    let demo: DemoSettingsUiState
    let send: (SettingsEvent) -> Void

    @State private var confirmingClear = false

    private var enabledBinding: Binding<Bool> {
        Binding(get: { demo.enabled }, set: { send(SettingsEventSetDemoMode(enabled: $0)) })
    }

    private var autoFireBinding: Binding<Int32> {
        Binding(get: { demo.autoFireSeconds }, set: { send(SettingsEventSetDemoAutoFire(seconds: $0)) })
    }

    /// Follows the VM's `confirmingClear`; a dismissal without a button cancels on the next turn,
    /// after any button action ran, like the shutdown dialog.
    private var clearBinding: Binding<Bool> {
        Binding(
            get: { confirmingClear },
            set: { presented in
                confirmingClear = presented
                if !presented {
                    DispatchQueue.main.async { send(SettingsEventCancelClearDemoData.shared) }
                }
            }
        )
    }

    var body: some View {
        Section {
            Toggle(isOn: enabledBinding) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(DemoSettingsUiState.companion.TITLE)
                        .font(.of(.body, weight: .semibold))
                    Text(DemoSettingsUiState.companion.SUMMARY)
                        .font(.of(.footnote))
                        .foregroundStyle(Theme.creamDim)
                }
            }
            .tint(Theme.gold)
            .accessibilityIdentifier(DemoSettingsTags.toggle)

            if demo.enabled {
                Picker(DemoSettingsUiState.companion.AUTO_FIRE_LABEL, selection: autoFireBinding) {
                    ForEach(demo.autoFireOptions, id: \.intValue) { seconds in
                        Text(DemoSettingsUiState.companion.autoFireLabel(seconds: seconds.int32Value))
                            .tag(seconds.int32Value)
                    }
                }
                .accessibilityIdentifier(DemoSettingsTags.autoFire)
            }

            Button(role: .destructive) {
                send(SettingsEventRequestClearDemoData.shared)
            } label: {
                Label(DemoSettingsUiState.companion.CLEAR_LABEL, systemImage: "trash")
                    .frame(minHeight: 44)
            }
            .accessibilityIdentifier(DemoSettingsTags.clear)
            .confirmationDialog(
                DemoSettingsUiState.companion.CLEAR_CONFIRM_TITLE,
                isPresented: clearBinding,
                titleVisibility: .visible
            ) {
                Button(DemoSettingsUiState.companion.CLEAR_CONFIRM_ACTION, role: .destructive) {
                    send(SettingsEventConfirmClearDemoData.shared)
                }
                .accessibilityIdentifier(DemoSettingsTags.clearConfirm)
                Button("Cancel", role: .cancel) { send(SettingsEventCancelClearDemoData.shared) }
                    .accessibilityIdentifier(DemoSettingsTags.clearCancel)
            } message: {
                Text(DemoSettingsUiState.companion.CLEAR_CONFIRM_MESSAGE)
            }
        } header: {
            Text("DEMO MODE")
                .font(.ofEyebrow)
                .tracking(1.7)
                .foregroundStyle(Theme.gold)
        }
        .listRowBackground(Theme.bgCard)
        .onChange(of: demo.confirmingClear, initial: true) { _, confirming in
            confirmingClear = confirming
        }
    }
}
