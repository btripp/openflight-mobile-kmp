// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Settings (plans R5b/R6c), over the shared `SettingsViewModel`: units, connection info,
/// simulator status, the radar/debug panel, cloud upload and Pi shutdown. Wi-Fi-only controls stay
/// visible and are disabled with the VM's reason. Android's `SettingsScreen.kt` renders the same
/// state.
///
/// Plan F1d: grouped into Device (connection, calibrate, camera, launch monitor, power, simulators,
/// radar, shutdown), Practice (units, audio call-outs, range theme) and Data (cloud upload, debug logging), like
/// Android. The Device group's "Calibrate radar" and "Camera" rows push `AppRoute`s onto the
/// Settings tab's own `NavigationStack`.
struct SettingsView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().settingsViewModel())
    @State private var message: String?

    var body: some View {
        SettingsContent(state: host.state, send: host.send, showDeviceLinks: true)
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
    /// Plan F1d: the Device group's Calibrate/Camera rows (they need an `AppRoute` destination).
    var showDeviceLinks = false

    @State private var showingShutdown = false
    /// Plan F8a2t: the shot trail preview is taller on a regular width (iPad).
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        Form {
            // Device
            connectionSection
            // Plan F14: try the app without a Pi.
            DemoModeSection(demo: state.demo, send: send)
            if showDeviceLinks { deviceLinksSection }
            launchMonitorSection
            if let power = state.power { powerStatusSection(power) }
            simulatorsSection
            radarSection
            shutdownSection
            // Practice
            unitsSection
            calloutsSection
            rangeThemeSection
            shotTrailSection
            // Data
            cloudSection
            // Hidden until the Pi reports its debug mode: never offer "Start" before that is known.
            if state.debug.loaded { debugSection }
        }
        // Plan F1c: capped and centered on a regular width (`core:designsystem`'s
        // `OfContentWidth`, 840 pt), so the form stays readable on an iPad instead of
        // stretching edge to edge.
        .contentWidth()
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
            header("UNITS", group: "Practice")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Range theme

    /// Plan F8a2a: how the driving range looks. The tags are the Kotlin options' own instances.
    private var rangeThemeBinding: Binding<RangeThemeSetting> {
        Binding(get: { state.rangeTheme.selected }, set: { send(SettingsEventSetRangeTheme(theme: $0)) })
    }

    private var rangeThemeSection: some View {
        Section {
            Picker("Range theme", selection: rangeThemeBinding) {
                ForEach(state.rangeTheme.options, id: \.theme.storageValue) { option in
                    Text(option.label).tag(option.theme)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("settings.rangeTheme")
        } header: {
            header("RANGE THEME")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Shot trail

    /// Plan F8a2t: how the range draws a shot's trail, with a live preview drawn by the range's own
    /// renderer. The tags are the Kotlin options' own instances (and counts).
    private var shotTrailBinding: Binding<ShotTrailStyle> {
        Binding(get: { state.shotTrail.selected }, set: { send(SettingsEventSetShotTrail(style: $0)) })
    }

    private var shotTrailKeepBinding: Binding<Int32> {
        Binding(get: { state.shotTrail.keepLast }, set: { send(SettingsEventSetShotTrailKeepLast(count: $0)) })
    }

    private var landingEffectBinding: Binding<LandingEffect> {
        Binding(get: { state.shotTrail.landingEffect }, set: { send(SettingsEventSetLandingEffect(effect: $0)) })
    }

    private var shotTrailSection: some View {
        Section {
            ShotTrailPreviewView(
                style: state.shotTrail.selected,
                keepLast: state.shotTrail.keepLast,
                landingEffect: state.shotTrail.landingEffect,
                theme: state.rangeTheme.selected
            )
            .frame(height: horizontalSizeClass == .regular ? 220 : 170)
            .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
            .accessibilityIdentifier("settings.shotTrail.preview")
            Picker("Style", selection: shotTrailBinding) {
                ForEach(state.shotTrail.styles, id: \.style.storageValue) { option in
                    Text(option.label).tag(option.style)
                }
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("settings.shotTrail")
            VStack(alignment: .leading, spacing: 6) {
                Text("Keep last shots").font(.footnote).foregroundStyle(Theme.creamDim)
                Picker("Keep last shots", selection: shotTrailKeepBinding) {
                    ForEach(state.shotTrail.keepOptions, id: \.count) { option in
                        Text(option.label).tag(option.count)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("settings.shotTrail.keepLast")
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("Landing effect").font(.footnote).foregroundStyle(Theme.creamDim)
                Picker("Landing effect", selection: landingEffectBinding) {
                    ForEach(state.shotTrail.landingEffects, id: \.effect.storageValue) { option in
                        Text(option.label).tag(option.effect)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("settings.shotTrail.landingEffect")
            }
        } header: {
            header("SHOT TRAIL")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Connection

    private var linkColor: Color {
        switch state.linkState {
        case is PiLinkStateConnected: Theme.success
        case is PiLinkStateConnecting, is PiLinkStateReconnecting: Theme.warning
        case is PiLinkStateRejected: Theme.danger
        default: Theme.neutral
        }
    }

    private var connectionSection: some View {
        Section {
            // Plan F14: Demo mode's pretend Pi is on Wi-Fi, whatever transport the real Pi uses.
            row("Transport", state.demo.enabled ? "Demo Pi (Wi-Fi)" : state.transport.label_)
            if !state.demo.enabled { row("Host", state.host) }
            row("Shot stream", state.connectionState.description_)
            LabeledContent("Live session") {
                StatusPill(text: state.linkDescription, color: linkColor)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings.liveSession")
            if let problem = state.connectionProblem {
                // Plan R8f: say what's wrong in words, not only with the pill's colour.
                NoticeRow(title: problem.title, detail: problem.detail, tone: .problem)
                    .accessibilityIdentifier("settings.connection.problem")
            }
            if state.mockMode {
                Text("The Pi runs in mock mode")
                    .font(.of(.subheadline, weight: .medium))
                    .foregroundStyle(Theme.warning)
            }
        } header: {
            header("CONNECTION", group: "Device")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Device links (plan F1d)

    private var deviceLinksSection: some View {
        Section {
            NavigationLink(value: AppRoute.calibration) {
                Label("Calibrate radar", systemImage: "scope")
            }
            .accessibilityIdentifier("settings.openCalibration")
            NavigationLink(value: AppRoute.camera) {
                Label("Camera", systemImage: "camera")
            }
            .accessibilityIdentifier("settings.openCamera")
        } header: {
            header("TOOLS")
        }
        .listRowBackground(Theme.bgCard)
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
            header("FLIGHTWEB CLOUD", group: "Data")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Audio call-outs (plan F7)

    private var calloutTriggerBinding: Binding<CalloutTrigger> {
        Binding(get: { state.callouts.trigger }, set: { send(SettingsEventSetCalloutTrigger(trigger: $0)) })
    }

    private var calloutVoiceBinding: Binding<String?> {
        Binding(get: { state.callouts.selectedVoiceId }, set: { send(SettingsEventSetCalloutVoice(voiceId: $0)) })
    }

    private var calloutRateBinding: Binding<Double> {
        Binding(get: { Double(state.callouts.rate) }, set: { send(SettingsEventSetCalloutRate(rate: Float($0))) })
    }

    /// `VoiceQuality.name`/`CalloutTrigger.name` (plan-proven idiom, see `RadarSliderRow`'s
    /// `slider.field.name`) sidestep guessing how Kotlin/Native's Swift export spells a
    /// multi-word or keyword-colliding enum case (`GAMES_ONLY`, `DEFAULT`, ...).
    private func qualityBadge(_ quality: VoiceQuality) -> String {
        switch quality.name {
        case "PREMIUM": "Premium"
        case "ENHANCED": "Enhanced"
        default: "Standard"
        }
    }

    private func triggerLabel(_ trigger: CalloutTrigger) -> String {
        trigger.name == "GAMES_ONLY" ? "Games only" : "Every shot"
    }

    private var calloutsSection: some View {
        let callouts = state.callouts
        return Section {
            Toggle(isOn: Binding(get: { callouts.enabled }, set: { send(SettingsEventSetCalloutsEnabled(enabled: $0)) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Speak shot results").font(.of(.body, weight: .semibold))
                    // Plan A16: deliberate, not a bug — a coaching/navigation voice, not a mutable notification.
                    Text("Call-outs play even with the silent switch on.")
                        .font(.of(.footnote))
                        .foregroundStyle(Theme.creamDim)
                }
            }
            .tint(Theme.gold)
            .accessibilityIdentifier("settings.callouts.enabled")

            if callouts.enabled {
                Picker("Trigger", selection: calloutTriggerBinding) {
                    ForEach(callouts.availableTriggers, id: \.name) { trigger in
                        Text(triggerLabel(trigger)).tag(trigger)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("settings.callouts.trigger")

                Picker("Voice", selection: calloutVoiceBinding) {
                    Text("Default voice").tag(String?.none)
                    ForEach(callouts.voiceGroups, id: \.locale) { group in
                        Section(group.locale) {
                            ForEach(group.voices, id: \.id) { voice in
                                Text("\(voice.displayName) (\(qualityBadge(voice.quality)))").tag(String?(voice.id))
                            }
                        }
                    }
                }
                .accessibilityIdentifier("settings.callouts.voice")

                Button {
                    send(SettingsEventPreviewCallout.shared)
                } label: {
                    Label("Preview", systemImage: "play.circle")
                }
                .accessibilityIdentifier("settings.callouts.previewButton")

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("Speech rate").font(.of(.body, weight: .medium))
                        Spacer()
                        Text("\(Int(callouts.rate * 100))%").font(.of(.body, weight: .semibold).monospacedDigit())
                    }
                    Slider(value: calloutRateBinding, in: 0.5 ... 2.0, step: 0.05)
                        .tint(Theme.gold)
                        .accessibilityIdentifier("settings.callouts.rate")
                }

                Text(callouts.previewText.isEmpty ? "Select at least one field below" : callouts.previewText)
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("settings.callouts.previewText")

                Text("Fields to speak, in order").font(.of(.footnote)).foregroundStyle(Theme.creamDim)
                ForEach(callouts.fields, id: \.field) { row in
                    calloutFieldRow(row)
                }
            }
        } header: {
            header("AUDIO CALL-OUTS")
        }
        .listRowBackground(Theme.bgCard)
    }

    private func calloutFieldRow(_ row: CalloutFieldRow) -> some View {
        HStack {
            Button {
                send(SettingsEventToggleCalloutField(field: row.field))
            } label: {
                HStack {
                    Image(systemName: row.selected ? "checkmark.square.fill" : "square")
                        .foregroundStyle(row.selected ? Theme.gold : Theme.creamDim)
                    Text(row.label).font(.of(.body))
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.callouts.field.\(row.field.name).toggle")
            Spacer()
            if row.selected {
                Button {
                    send(SettingsEventMoveCalloutField(field: row.field, up: true))
                } label: {
                    Image(systemName: "chevron.up")
                }
                .disabled(!row.canMoveUp)
                .accessibilityIdentifier("settings.callouts.field.\(row.field.name).up")
                Button {
                    send(SettingsEventMoveCalloutField(field: row.field, up: false))
                } label: {
                    Image(systemName: "chevron.down")
                }
                .disabled(!row.canMoveDown)
                .accessibilityIdentifier("settings.callouts.field.\(row.field.name).down")
            }
        }
        .accessibilityIdentifier("settings.callouts.field.\(row.field.name)")
    }

    // MARK: Launch monitor and power (plan R8f, Expo `device.tsx`)

    private var launchMonitorSection: some View {
        Section {
            switch state.trigger {
            case let loaded as TriggerCardLoaded:
                ForEach(loaded.rows, id: \.label) { deviceRow in
                    row(deviceRow.label, deviceRow.value)
                }
            case let unavailable as TriggerCardUnavailable:
                DisabledReason(reason: unavailable.reason)
            default:
                Text(TriggerCardCompanion.shared.WAITING_TEXT)
                    .font(.of(.subheadline))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("settings.trigger.waiting")
            }
        } header: {
            header("LAUNCH MONITOR")
        }
        .listRowBackground(Theme.bgCard)
    }

    private func powerStatusSection(_ power: PowerCard) -> some View {
        Section {
            LabeledContent {
                HStack(spacing: 6) {
                    if power.warning {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(Theme.warning)
                            .accessibilityLabel("Warning")
                    }
                    Text(power.stateLabel)
                        .font(.of(.body, weight: .semibold))
                        .foregroundStyle(Theme.cream)
                }
            } label: {
                Text("State").font(.of(.body)).foregroundStyle(Theme.creamDim)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings.power.state")
            ForEach(power.rows, id: \.label) { deviceRow in
                row(deviceRow.label, deviceRow.value)
            }
        } header: {
            header("POWER")
        }
        .listRowBackground(Theme.bgCard)
    }

    // MARK: Stopping OpenFlight (plan R8f shutdown phase machine)

    @ViewBuilder
    private var shutdownContent: some View {
        switch state.shutdown.phase {
        case let pending as ShutdownPhasePending:
            NoticeRow(title: ShutdownPhaseCompanion.shared.PENDING_TEXT, detail: pending.target, tone: .busy)
                .accessibilityIdentifier("settings.shutdown.pending")
        case is ShutdownPhaseDone:
            NoticeRow(
                title: ShutdownPhaseCompanion.shared.DONE_TITLE,
                detail: ShutdownPhaseCompanion.shared.DONE_DETAIL,
                tone: .success
            )
            .accessibilityIdentifier("settings.shutdown.done")
            Button("OK") { send(SettingsEventDismissShutdown.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier("settings.shutdown.dismiss")
        case let failed as ShutdownPhaseFailed:
            NoticeRow(
                title: ShutdownPhaseCompanion.shared.FAILED_TITLE,
                detail: "\(failed.reason) \(ShutdownPhaseCompanion.shared.STILL_RUNNING)",
                tone: .problem
            )
            .accessibilityIdentifier("settings.shutdown.failed")
            Button("Try again") { send(SettingsEventRetryShutdown.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier("settings.shutdown.retry")
            Button("Dismiss") { send(SettingsEventDismissShutdown.shared) }
                .frame(minHeight: 44)
                .accessibilityIdentifier("settings.shutdown.dismiss")
        default:
            stopButton
        }
    }

    private var stopButton: some View {
        let shutdown = state.shutdown.shutdown
        return Group {
            Text("Stops the OpenFlight server. The Pi itself stays on.")
                .font(.of(.footnote))
                .foregroundStyle(Theme.creamDim)
            Button(role: .destructive) {
                send(SettingsEventRequestShutdown.shared)
            } label: {
                Label("Stop OpenFlight", systemImage: "power")
                    .frame(minHeight: 44)
            }
            .disabled(!shutdown.isAvailable)
            .accessibilityIdentifier("settings.shutdown")
            // Attached to the button, so where iOS shows it as a popover it points at it.
            .confirmationDialog(
                ShutdownSettings.companion.CONFIRMATION_TEXT,
                isPresented: shutdownBinding,
                titleVisibility: .visible
            ) {
                Button("Stop now", role: .destructive) { send(SettingsEventConfirmShutdown.shared) }
                    .accessibilityIdentifier("settings.shutdown.confirm")
                Button("Cancel", role: .cancel) { send(SettingsEventCancelShutdown.shared) }
                    .accessibilityIdentifier("settings.shutdown.cancel")
            } message: {
                Text(ShutdownPhaseCompanion.shared.CONFIRM_MESSAGE)
            }
            if let reason = shutdown.disabledReason {
                DisabledReason(reason: reason)
                    .accessibilityIdentifier("settings.shutdown.reason")
            }
        }
    }

    private var shutdownSection: some View {
        Section {
            shutdownContent
        } header: {
            header("OPENFLIGHT SERVER")
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

    /// Plan F1d: the first section of a group carries the group's title (Device, Practice, Data)
    /// above its own eyebrow.
    private func header(_ text: String, group: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(group)
                .font(.of(.title2, weight: .bold))
                .foregroundStyle(Theme.cream)
                .textCase(nil)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("settings.group.\(group.lowercased())")
            header(text)
        }
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
