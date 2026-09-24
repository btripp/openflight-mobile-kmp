// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Settings (plans R5b/R6c), over the shared `SettingsViewModel`: units, connection info, player,
/// simulator status, the radar/debug panel, cloud upload and Pi shutdown. Wi-Fi-only controls stay
/// visible and are disabled with the VM's reason. Android's `SettingsScreen.kt` renders the same
/// state.
struct SettingsView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().settingsViewModel())
    @State private var message: String?

    var body: some View {
        SettingsContent(state: host.state, send: host.send)
            .navigationTitle("Settings")
            .messageBanner($message)
            .task {
                await host.collect(host.viewModel.sideEffects) { effect in
                    if let note = effect as? SettingsEffectMessage { message = note.text }
                }
            }
    }
}

struct SettingsContent: View {
    let state: SettingsUiState
    let send: (SettingsEvent) -> Void

    @State private var playerName = ""
    @State private var showingShutdown = false

    var body: some View {
        Form {
            unitsSection
            connectionSection
            playerSection
            simulatorsSection
            radarSection
            debugSection
            cloudSection
            powerSection
        }
        .screenBackground()
        .onChange(of: state.shutdown.confirmationRequired, initial: true) { _, required in
            showingShutdown = required
        }
    }

    /// The dialog follows the VM's `confirmationRequired`. A dismissal that didn't go through a
    /// button (for example a tap outside) cancels on the next turn, after any button action ran,
    /// so it never races "Shut Down".
    private var shutdownBinding: Binding<Bool> {
        Binding(
            get: { showingShutdown },
            set: { presented in
                showingShutdown = presented
                if !presented {
                    DispatchQueue.main.async { send(SettingsEventCancelShutdown.shared) }
                }
            }
        )
    }

    // MARK: Units

    private var unitsBinding: Binding<UnitSystem> {
        Binding(get: { state.units }, set: { send(SettingsEventSetUnits(units: $0)) })
    }

    private var unitsSection: some View {
        Section {
            Picker("Units", selection: unitsBinding) {
                Text("Imperial (mph, yds)").tag(UnitSystem.imperial)
                Text("Metric (km/h, m)").tag(UnitSystem.metric)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("settings.units")
        } header: {
            header("UNITS")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Connection

    private var linkColor: Color {
        switch state.linkState {
        case is PiLinkStateConnected: Theme.success
        case is PiLinkStateConnecting, is PiLinkStateReconnecting: Theme.warning
        default: Theme.neutral
        }
    }

    private var connectionSection: some View {
        Section {
            row("Transport", state.transport.label_)
            row("Host", state.host)
            row("Shot stream", state.connectionState.description_)
            LabeledContent("Live session") {
                StatusPill(text: state.linkDescription, color: linkColor)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings.liveSession")
            if state.mockMode {
                Text("The Pi runs in mock mode")
                    .font(.of(.subheadline, weight: .medium))
                    .foregroundStyle(Theme.warning)
            }
        } header: {
            header("CONNECTION")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Player

    private var playerSection: some View {
        let setPlayer = state.player.setPlayer
        return Section {
            row("Current player", state.player.currentName ?? "—")
            TextField("Player name", text: $playerName)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onChange(of: playerName) { _, name in
                    let limit = Int(PlayerSettings.companion.MAX_NAME_LENGTH)
                    if name.count > limit { playerName = String(name.prefix(limit)) }
                }
                .onSubmit(submitPlayer)
                .disabled(!setPlayer.isAvailable)
                .accessibilityIdentifier("settings.player.field")
            Button("Set Player", action: submitPlayer)
                .font(.of(.body, weight: .semibold))
                .disabled(!setPlayer.isAvailable)
                .accessibilityIdentifier("settings.player.set")
            if let reason = setPlayer.disabledReason { DisabledReason(reason: reason) }
        } header: {
            header("PLAYER")
        }
        .listRowBackground(Theme.bgCard)
    }

    private func submitPlayer() {
        send(SettingsEventSetPlayer(name: playerName))
        playerName = ""
    }

    // MARK: Simulators

    private func severityColor(_ severity: SimSeverity) -> Color {
        switch severity {
        case .ok: Theme.success
        case .warn: Theme.warning
        case .error: Theme.danger
        default: Theme.neutral
        }
    }

    private var simulatorsSection: some View {
        Section {
            if state.simulators.isEmpty {
                Text("No simulator connectors configured on the Pi")
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
            } else {
                ForEach(state.simulators, id: \.target) { sim in
                    VStack(alignment: .leading, spacing: 4) {
                        StatusPill(text: "\(sim.displayName) · \(sim.state)", color: severityColor(sim.severity))
                        Text(sim.detail)
                            .font(.of(.footnote))
                            .foregroundStyle(Theme.creamDim)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("settings.sim.\(sim.target)")
                }
            }
        } header: {
            header("SIMULATORS")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Radar

    private var radarSection: some View {
        let radar = state.radar
        return Section {
            if let status = radar.triggerStatus {
                row("Mode", status.mode)
                row("Radar", status.radarConnected ? "Connected \(status.radarPort ?? "")" : "Not connected")
                row(
                    "Triggers",
                    "\(status.triggersTotal) total · \(status.triggersAccepted) accepted · \(status.triggersRejected) rejected"
                )
            }
            if let notice = radar.tuningNotice {
                Text(notice)
                    .font(.of(.subheadline, weight: .medium))
                    .foregroundStyle(Theme.warning)
            }
            if radar.config == nil {
                Text("No radar config from the Pi yet")
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
            } else {
                ForEach(radar.sliders, id: \.field) { slider in
                    RadarSliderRow(slider: slider) { value in
                        send(SettingsEventSetRadarValue(field: slider.field, value: value))
                    }
                }
                Text(radar.hint)
                    .font(.of(.footnote))
                    .foregroundStyle(Theme.creamDim)
            }
            Button {
                send(SettingsEventRefreshRadarConfig.shared)
            } label: {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .disabled(!radar.refresh.isAvailable)
            .accessibilityIdentifier("settings.radar.refresh")
            if let reason = radar.refresh.disabledReason { DisabledReason(reason: reason) }
            if !radar.diagnostics.isEmpty {
                DisclosureGroup("Recent triggers (\(radar.diagnostics.count))") {
                    ForEach(Array(radar.diagnostics.enumerated()), id: \.offset) { _, trigger in
                        HStack(spacing: 8) {
                            Image(systemName: trigger.accepted ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(trigger.accepted ? Theme.success : Theme.danger)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(trigger.reasonText).font(.of(.subheadline))
                                if let time = trigger.timestamp {
                                    Text(Units.clockTime(time))
                                        .font(.of(.caption))
                                        .foregroundStyle(Theme.creamDim)
                                }
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .font(.of(.body, weight: .medium))
            }
        } header: {
            header("RADAR")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Debug

    private var debugSection: some View {
        let debug = state.debug
        return Section {
            ToggleRow(
                label: "Debug logging",
                isOn: debug.enabled,
                availability: debug.toggle,
                identifier: "settings.debug.toggle"
            ) { send(SettingsEventToggleDebug.shared) }
            if debug.enabled {
                if let path = debug.logPath {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Log file").font(.of(.footnote)).foregroundStyle(Theme.creamDim)
                        Text(path)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("settings.debug.logPath")
                }
                Text("\(debug.readingCount) readings · \(debug.shotLogCount) shot logs")
                    .font(.of(.footnote))
                    .foregroundStyle(Theme.creamDim)
            }
        } header: {
            header("DEBUG")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Cloud

    private var cloudStatus: String? {
        let cloud = state.cloud
        let label: String? = switch cloud.state {
        case .running: SettingsViewModel.companion.UPLOADING
        case .complete: "Uploaded"
        case .error: "Upload failed"
        default: nil
        }
        return [label, cloud.message].compactMap { $0 }.joined(separator: ": ").nilIfEmpty
    }

    private var cloudSection: some View {
        let cloud = state.cloud
        return Section {
            Button {
                send(SettingsEventUploadCloud.shared)
            } label: {
                HStack {
                    Label("Upload Session", systemImage: "icloud.and.arrow.up")
                    if cloud.state == .running {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(!cloud.upload.isAvailable)
            .accessibilityIdentifier("settings.cloud.upload")
            if let reason = cloud.upload.disabledReason { DisabledReason(reason: reason) }
            if let status = cloudStatus {
                Text(status)
                    .font(.of(.subheadline))
                    .foregroundStyle(cloud.state == .error ? Theme.danger : Theme.creamDim)
                    .accessibilityIdentifier("settings.cloud.status")
            }
        } header: {
            header("FLIGHTWEB CLOUD")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Power

    private var powerSection: some View {
        let shutdown = state.shutdown.shutdown
        return Section {
            Button(role: .destructive) {
                send(SettingsEventRequestShutdown.shared)
            } label: {
                Label("Shut Down Pi", systemImage: "power")
            }
            .disabled(!shutdown.isAvailable)
            .accessibilityIdentifier("settings.shutdown")
            // Attached to the button, so where iOS shows it as a popover it points at it.
            .confirmationDialog(
                ShutdownSettings.companion.CONFIRMATION_TEXT,
                isPresented: shutdownBinding,
                titleVisibility: .visible
            ) {
                Button("Shut Down", role: .destructive) { send(SettingsEventConfirmShutdown.shared) }
                    .accessibilityIdentifier("settings.shutdown.confirm")
                Button("Cancel", role: .cancel) { send(SettingsEventCancelShutdown.shared) }
                    .accessibilityIdentifier("settings.shutdown.cancel")
            } message: {
                Text("The Pi powers off. You'll need to switch it back on by hand to use OpenFlight again.")
            }
            if let reason = shutdown.disabledReason {
                DisabledReason(reason: reason)
                    .accessibilityIdentifier("settings.shutdown.reason")
            }
        } header: {
            header("POWER")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Helpers

    private func header(_ text: String) -> some View {
        Text(text)
            .font(.ofEyebrow)
            .tracking(1.7)
            .foregroundStyle(Theme.gold)
    }

    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent {
            Text(value)
                .font(.of(.body, weight: .semibold))
                .foregroundStyle(Theme.cream)
                .multilineTextAlignment(.trailing)
        } label: {
            Text(label).font(.of(.body)).foregroundStyle(Theme.creamDim)
        }
    }
}

/// One radar tuning slider (`DebugPanel.tsx`'s `SliderControl`): the VM's range and step, sending
/// `set_radar_config` only when released.
private struct RadarSliderRow: View {
    let slider: RadarSlider
    let commit: (Int32) -> Void

    @State private var value: Double = 0
    @State private var isEditing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(slider.label).font(.of(.body, weight: .medium))
                Spacer()
                Text("\(Int(value))\(slider.unit)")
                    .font(.of(.body, weight: .semibold).monospacedDigit())
            }
            Slider(
                value: $value,
                in: Double(slider.min) ... Double(slider.max),
                step: Double(slider.step)
            ) { editing in
                isEditing = editing
                if !editing { commit(Int32(value)) }
            }
            .tint(Theme.gold)
            .disabled(!slider.availability.isAvailable)
            .accessibilityLabel(slider.label)
            .accessibilityIdentifier("settings.radar.\(slider.field.name)")
            if let reason = slider.availability.disabledReason { DisabledReason(reason: reason) }
        }
        .onChange(of: slider.value, initial: true) { _, newValue in
            if !isEditing { value = Double(newValue) }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
