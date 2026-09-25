// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Shot formatting through the shared `ShotMetricFormatter` (en-US, "—" for a missing value).
enum ShotFormat {
    static func number(_ value: Double, decimals: Int32) -> String {
        ShotMetricFormatter.shared.number(value: KotlinDouble(value: value), decimals: decimals, signed: false)
    }

    static func number(_ value: KotlinDouble?, decimals: Int32) -> String {
        ShotMetricFormatter.shared.number(value: value, decimals: decimals, signed: false)
    }
}

/// The spoken form of a unit label, for VoiceOver.
private func spoken(_ unit: String) -> String {
    switch unit.lowercased() {
    case "mph": "miles per hour"
    case "km/h": "kilometers per hour"
    case "yds": "yards"
    case "m": "meters"
    default: unit
    }
}

/// The latest shot (ContentView.swift `shotCard`), plus the web UI's confidence badges, spin
/// source, carry range and player (plans R5b/R6c). Values follow the unit preference.
struct ShotCard: View {
    let shot: ShotEvent
    var units: UnitSystem = .imperial
    var enrichment: ShotEnrichment?

    private static let spinAdjusted = "spin-adjusted"

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Eyebrow("LATEST SHOT")
                    Text(shot.displayClub)
                        .font(.ofDisplay(.title))
                        .accessibilityIdentifier("dashboard.latestShot.club")
                    if let player = enrichment?.profileName {
                        Text(player)
                            .font(.of(.subheadline))
                            .foregroundStyle(Theme.creamDim)
                            .accessibilityIdentifier("dashboard.player")
                    }
                }
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.gold)
                    .accessibilityHidden(true)
            }

            primaryMetrics

            Divider().overlay(Theme.cream.opacity(0.12))

            Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 16) {
                GridRow {
                    DetailMetric(
                        title: "Club speed",
                        value: Units.speed(shot.clubSpeedMph, units),
                        unit: Units.speedUnit(units)
                    )
                    DetailMetric(title: "Smash", value: ShotFormat.number(shot.smashFactor, decimals: 2))
                }
                GridRow {
                    DetailMetric(
                        title: "Launch",
                        value: Units.degrees(shot.launchAngleVertical),
                        isDegrees: true,
                        confidence: enrichment?.launchAngleConfidence
                    )
                    DetailMetric(title: "Direction", value: Units.degrees(shot.launchAngleHorizontal), isDegrees: true)
                }
                GridRow {
                    DetailMetric(
                        title: "Spin",
                        value: ShotFormat.number(shot.spinRpm, decimals: 0),
                        unit: "rpm",
                        confidence: enrichment?.spinQuality,
                        subtext: enrichment?.spinSource?.label
                    )
                    DetailMetric(title: "Club path", value: Units.degrees(shot.clubPathDeg), isDegrees: true)
                }
                GridRow {
                    DetailMetric(title: "Spin axis", value: Units.degrees(shot.spinAxisDeg), isDegrees: true)
                    Color.clear
                }
            }
        }
        .padding(18)
        .background(Theme.cream.opacity(0.08), in: RoundedRectangle(cornerRadius: 24))
        .overlay {
            RoundedRectangle(cornerRadius: 24)
                .stroke(Theme.gold.opacity(0.18), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(DashboardTestTags.shared.LATEST_SHOT)
    }

    /// Ball speed and carry. Carry follows `ShotDisplay.tsx`: the spin-adjusted carry when the Pi
    /// has one (subtext "spin-adjusted"), otherwise the estimate with the carry range as its subtext.
    private var primaryMetrics: some View {
        let spinAdjusted = enrichment?.carrySpinAdjustedYards?.doubleValue
        let carryYards = spinAdjusted ?? shot.estimatedCarryYards
        let carrySubtext = spinAdjusted != nil ? Self.spinAdjusted : enrichment?.carryRangeText(units: units)
        return HStack(alignment: .top, spacing: 10) {
            PrimaryMetric(
                title: "BALL SPEED",
                value: Units.speed(shot.ballSpeedMph, units),
                unit: Units.speedUnit(units)
            )
            .accessibilityIdentifier("dashboard.ballSpeed")
            PrimaryMetric(
                title: "CARRY",
                value: Units.distance(carryYards, units),
                unit: Units.distanceUnit(units),
                subtext: carrySubtext
            )
            .accessibilityIdentifier("dashboard.carry")
        }
    }
}

/// The per-club shot counts (`StatsView.tsx`'s club tabs, display-only here).
struct ClubChipsCard: View {
    let chips: [ClubChip]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Eyebrow("SESSION")
                Spacer()
                Text("\(chips.reduce(0) { $0 + Int($1.count) }) shots")
                    .font(.of(.caption, weight: .semibold))
                    .foregroundStyle(Theme.creamDim)
            }
            FlowLayout {
                ForEach(chips, id: \.club) { chip in
                    HStack(spacing: 6) {
                        Text(Units.clubLabel(chip.club))
                            .font(.of(.subheadline, weight: .semibold))
                        Text("\(chip.count)")
                            .font(.of(.caption, weight: .bold).monospacedDigit())
                            .foregroundStyle(Theme.gold)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Theme.cream.opacity(0.06), in: Capsule())
                    .overlay { Capsule().stroke(Theme.cream.opacity(0.14), lineWidth: 1) }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("dashboard.clubChip.\(chip.club)")
                }
            }
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dashboard.clubChips")
    }
}

/// The previous shots, newest first (ContentView.swift `shotHistoryCard`).
struct ShotHistoryCard: View {
    let shots: [ShotEvent]
    var units: UnitSystem = .imperial

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Eyebrow("PREVIOUS SHOTS")
                Spacer()
                Text(shots.count.formatted())
                    .font(.of(.caption, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("dashboard.previousShots.count")
            }
            .padding(.bottom, 8)

            LazyVStack(spacing: 0) {
                ForEach(Array(shots.enumerated()), id: \.element.eventId) { index, shot in
                    PreviousShotRow(shot: shot, units: units)
                        .transition(.opacity)

                    if index < shots.count - 1 {
                        Divider().overlay(Theme.cream.opacity(0.12))
                    }
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .stroke(Theme.cream.opacity(0.07), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Previous shots")
        .accessibilityIdentifier(DashboardTestTags.shared.PREVIOUS_SHOTS)
    }
}

private struct PrimaryMetric: View {
    let title: String
    let value: String
    let unit: String
    var subtext: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.of(.caption2, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(Theme.creamDim)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.ofDisplay(.largeTitle, size: 40))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.62)
                    .allowsTightening(true)
                    .layoutPriority(1)
                Text(unit.uppercased())
                    .font(.of(.caption2, weight: .bold))
                    .foregroundStyle(Theme.creamDim)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
            if let subtext {
                Text(subtext)
                    .font(.of(.caption, weight: .medium))
                    .foregroundStyle(Theme.creamDim)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityIdentifier("dashboard.carry.subtext")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 82)
        .padding(.horizontal, 12)
        .padding(.vertical, 13)
        .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title.capitalized)
        .accessibilityValue(
            value == ShotMetricFormatter.shared.MISSING
                ? "not measured"
                : "\(value) \(spoken(unit))" + (subtext.map { ", \($0)" } ?? "")
        )
    }
}

/// A detail metric. Degrees come pre-formatted and tight ("9.5°", the shared `formatDegrees`),
/// word units follow after a space ("2,380 rpm"); a missing value shows "—" alone. The web UI's
/// confidence dots and subtext (spin source) sit underneath.
private struct DetailMetric: View {
    let title: String
    let value: String
    var unit = ""
    var isDegrees = false
    var confidence: ConfidenceLevel?
    var subtext: String?

    private var isMissing: Bool { value == ShotMetricFormatter.shared.MISSING }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.of(.callout))
                .foregroundStyle(Theme.creamDim)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.of(.title3, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                if !unit.isEmpty, !isMissing {
                    Text(unit)
                        .font(.of(.callout))
                        .foregroundStyle(Theme.creamDim)
                        .lineLimit(1)
                }
            }
            if let subtext {
                Text(subtext)
                    .font(.of(.caption, weight: .medium))
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("dashboard.subtext.\(title)")
            }
            if let confidence {
                ConfidenceDots(level: confidence)
                    .accessibilityIdentifier("dashboard.confidence.\(title)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(accessibilityValue)
        .accessibilityIdentifier(DashboardTestTags.shared.metric(title: title))
    }

    private var accessibilityValue: String {
        if isMissing { return "not measured" }
        var text: String
        if isDegrees {
            text = "\(value.replacingOccurrences(of: "°", with: "")) degrees"
        } else {
            text = unit.isEmpty ? value : "\(value) \(unit)"
        }
        if let subtext { text += ", \(subtext)" }
        if let confidence { text += ", \(confidence.label) confidence" }
        return text
    }
}

private struct PreviousShotRow: View {
    let shot: ShotEvent
    let units: UnitSystem

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(shot.displayClub)
                    .font(.of(.headline, weight: .semibold))
                    .lineLimit(1)
                Text("Completed shot")
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HistoryMetric(value: Units.speed(shot.ballSpeedMph, units), unit: Units.speedUnit(units).uppercased())
            HistoryMetric(
                value: Units.distance(shot.estimatedCarryYards, units),
                unit: Units.distanceUnit(units).uppercased()
            )
        }
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("dashboard.previousShot")
    }
}

private struct HistoryMetric: View {
    let value: String
    let unit: String

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            Text(value)
                .font(.of(.title3, weight: .bold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(unit)
                .font(.of(.caption2, weight: .semibold))
                .foregroundStyle(Theme.creamDim)
        }
        .frame(minWidth: 62, alignment: .trailing)
    }
}
