// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Swing-speed training (plan R6c), over the shared `TrainingViewModel`: the player and trigger
/// mode, Last/Best/Average, and the grouped implement picker. Wi-Fi only; every control is disabled
/// with the VM's reason otherwise. Android's `TrainingScreen.kt` renders the same state.
struct TrainingView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().trainingViewModel())

    var body: some View {
        TrainingContent(state: host.state, send: host.send)
            .navigationTitle("Swing Training")
    }
}

struct TrainingContent: View {
    let state: TrainingUiState
    let send: (TrainingEvent) -> Void

    private var units: UnitSystem { state.units }
    private var available: Bool { state.availability.isAvailable }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let error = state.error {
                    errorBanner(error)
                }
                sessionCard
                speedCard
                implementCard
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .screenBackground()
        .accessibilityIdentifier("training.screen")
    }

    // MARK: Session

    private var modeText: String {
        guard let mode = state.triggerMode else { return "Waiting for the Pi's trigger mode" }
        return state.isSwingSpeedMode
            ? "Swing-speed mode: every swing is a rep"
            : "Trigger mode \"\(mode)\": start the Pi in swing-speed mode for reps"
    }

    private var sessionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow("SESSION")
            Text(state.profileName)
                .font(.ofDisplay(.title))
                .accessibilityIdentifier("training.player")
            Text(modeText)
                .font(.of(.subheadline))
                .foregroundStyle(state.isSwingSpeedMode ? Theme.success : Theme.creamDim)
            if let reason = state.availability.disabledReason {
                DisabledReason(reason: reason)
                    .accessibilityIdentifier("training.availability")
            }
            if state.showSimulateSwing {
                Button {
                    send(TrainingEventSimulateSwing.shared)
                } label: {
                    Text("Simulate Swing")
                        .font(.of(.body, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.gold)
                .foregroundStyle(Theme.bgDeep)
                .disabled(!available)
                .accessibilityIdentifier("training.simulate")
            }
        }
        .card()
    }

    private func errorBanner(_ error: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.danger)
            Text(error)
                .font(.of(.subheadline, weight: .medium))
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Dismiss") { send(TrainingEventDismissError.shared) }
                .font(.of(.subheadline, weight: .semibold))
                .tint(Theme.gold)
        }
        .padding(14)
        .background(Theme.danger.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .overlay { RoundedRectangle(cornerRadius: 14).stroke(Theme.danger.opacity(0.4), lineWidth: 1) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("training.error")
    }

    // MARK: Speed

    private func speed(_ mph: Double) -> String {
        state.hasSwings ? Units.speed(mph, units) : ShotMetricFormatter.shared.MISSING
    }

    private var speedCard: some View {
        let stats = state.stats
        let unit = Units.speedUnit(units)
        return VStack(alignment: .leading, spacing: 12) {
            Eyebrow("SWING SPEED")
            HStack(spacing: 10) {
                SpeedTile(label: "Last", value: speed(stats.lastSpeedMph), unit: unit)
                SpeedTile(label: "Best", value: speed(stats.bestSpeedMph), unit: unit)
                SpeedTile(label: "Average", value: speed(stats.avgSpeedMph), unit: unit)
            }
            Text(state.hasSwings ? "\(stats.count) swings (this player and implement)" : "No swings yet")
                .font(.of(.subheadline))
                .foregroundStyle(Theme.creamDim)
                .accessibilityIdentifier("training.count")
            if let rep = state.lastRep {
                Text(repDetail(rep))
                    .font(.of(.footnote))
                    .foregroundStyle(Theme.creamDim)
            }
        }
        .card()
    }

    /// The newest rep's details (`ShotDisplay.tsx`'s swing-speed subtexts).
    private func repDetail(_ rep: SwingRep) -> String {
        [
            "Latest: \(Units.speedText(rep.speedMph, units))",
            rep.implementLabel,
            rep.readingCount.map { "\($0.intValue) radar readings" },
            rep.triggerSpeedMph.map { "\(Units.speedText($0.doubleValue, units)) trigger" },
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    // MARK: Implement

    private var implementCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow("IMPLEMENT")
            Text(state.selectedImplement.label)
                .font(.ofDisplay(.title2))
                .accessibilityIdentifier("training.selectedImplement")
            if let reason = state.availability.disabledReason {
                DisabledReason(reason: reason)
            }
            ForEach(state.implementGroups, id: \.name) { group in
                VStack(alignment: .leading, spacing: 8) {
                    Text(group.name)
                        .font(.of(.footnote, weight: .bold))
                        .foregroundStyle(Theme.creamDim)
                        .accessibilityAddTraits(.isHeader)
                    FlowLayout {
                        ForEach(group.options, id: \.id) { option in
                            ChipButton(
                                label: option.label,
                                isSelected: option.id == state.selectedImplement.id,
                                isEnabled: available
                            ) {
                                send(TrainingEventSelectImplement(id: option.id))
                            }
                            .accessibilityIdentifier("training.implement.\(option.id)")
                        }
                    }
                }
            }
        }
        .card()
    }
}

private struct SpeedTile: View {
    let label: String
    let value: String
    let unit: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.of(.caption2, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(Theme.creamDim)
            Text(value)
                .font(.ofDisplay(.title))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(unit)
                .font(.of(.caption, weight: .semibold))
                .foregroundStyle(Theme.creamDim)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(value == ShotMetricFormatter.shared.MISSING ? "no swings" : "\(value) \(unit)")
        .accessibilityIdentifier("training.\(label.lowercased())")
    }
}
