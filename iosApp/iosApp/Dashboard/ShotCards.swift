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

/// The latest shot (ContentView.swift `shotCard`).
struct ShotCard: View {
    let shot: ShotEvent

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("LATEST SHOT")
                        .font(.caption.weight(.bold))
                        .tracking(1.7)
                        .foregroundStyle(Theme.gold)
                    Text(shot.displayClub)
                        .font(.title2.bold())
                        .accessibilityIdentifier("dashboard.latestShot.club")
                }
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(Theme.gold)
                    .accessibilityHidden(true)
            }

            GeometryReader { geometry in
                let metricWidth = (geometry.size.width - 10) / 2

                HStack(spacing: 10) {
                    PrimaryMetric(
                        title: "BALL SPEED",
                        value: ShotFormat.number(shot.ballSpeedMph, decimals: 1),
                        unit: "MPH",
                        spokenUnit: "miles per hour"
                    )
                    .frame(width: metricWidth)
                    .accessibilityIdentifier("dashboard.ballSpeed")
                    PrimaryMetric(
                        title: "CARRY",
                        value: ShotFormat.number(shot.estimatedCarryYards, decimals: 0),
                        unit: "YDS",
                        spokenUnit: "yards"
                    )
                    .frame(width: metricWidth)
                    .accessibilityIdentifier("dashboard.carry")
                }
            }
            .frame(height: 108)

            Divider().overlay(Theme.cream.opacity(0.12))

            Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 16) {
                GridRow {
                    DetailMetric(title: "Club speed", value: ShotFormat.number(shot.clubSpeedMph, decimals: 1), unit: "mph")
                    DetailMetric(title: "Smash", value: ShotFormat.number(shot.smashFactor, decimals: 2))
                }
                GridRow {
                    DetailMetric(title: "Launch", value: ShotFormat.number(shot.launchAngleVertical, decimals: 1), unit: "°")
                    DetailMetric(title: "Direction", value: ShotFormat.number(shot.launchAngleHorizontal, decimals: 1), unit: "°")
                }
                GridRow {
                    DetailMetric(title: "Spin", value: ShotFormat.number(shot.spinRpm, decimals: 0), unit: "rpm")
                    DetailMetric(title: "Club path", value: ShotFormat.number(shot.clubPathDeg, decimals: 1), unit: "°")
                }
                GridRow {
                    DetailMetric(title: "Spin axis", value: ShotFormat.number(shot.spinAxisDeg, decimals: 1), unit: "°")
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
}

/// The previous shots, newest first (ContentView.swift `shotHistoryCard`).
struct ShotHistoryCard: View {
    let shots: [ShotEvent]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("PREVIOUS SHOTS")
                    .font(.caption.weight(.bold))
                    .tracking(1.7)
                    .foregroundStyle(Theme.gold)
                Spacer()
                Text(shots.count.formatted())
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Theme.creamDim)
                    .accessibilityIdentifier("dashboard.previousShots.count")
            }
            .padding(.bottom, 8)

            LazyVStack(spacing: 0) {
                ForEach(Array(shots.enumerated()), id: \.element.eventId) { index, shot in
                    PreviousShotRow(shot: shot)
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
    let spokenUnit: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption2.weight(.bold))
                .foregroundStyle(Theme.creamDim)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.62)
                    .allowsTightening(true)
                    .layoutPriority(1)
                Text(unit)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Theme.creamDim)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 82)
        .padding(.horizontal, 12)
        .padding(.vertical, 13)
        .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title.capitalized)
        .accessibilityValue(value == ShotMetricFormatter.shared.MISSING ? "not measured" : "\(value) \(spokenUnit)")
    }
}

/// A detail metric. Degree units attach tight ("9.5°"), word units after a space ("2,380 rpm");
/// a missing value shows "—" alone.
private struct DetailMetric: View {
    let title: String
    let value: String
    var unit = ""

    private var isMissing: Bool { value == ShotMetricFormatter.shared.MISSING }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.callout)
                .foregroundStyle(Theme.creamDim)
            HStack(alignment: .firstTextBaseline, spacing: unit == "°" ? 0 : 3) {
                Text(value)
                    .font(.title3.weight(.semibold).monospacedDigit())
                    .lineLimit(1)
                if !unit.isEmpty, !isMissing {
                    Text(unit)
                        .font(unit == "°" ? .title3.weight(.semibold) : .callout)
                        .foregroundStyle(Theme.creamDim)
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isMissing ? "not measured" : "\(value)\(unit == "°" ? " degrees" : unit.isEmpty ? "" : " \(unit)")")
        .accessibilityIdentifier(DashboardTestTags.shared.metric(title: title))
    }
}

private struct PreviousShotRow: View {
    let shot: ShotEvent

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(shot.displayClub)
                    .font(.headline)
                    .lineLimit(1)
                Text("Completed shot")
                    .font(.caption)
                    .foregroundStyle(Theme.creamDim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HistoryMetric(value: ShotFormat.number(shot.ballSpeedMph, decimals: 1), unit: "MPH")
            HistoryMetric(value: ShotFormat.number(shot.estimatedCarryYards, decimals: 0), unit: "YDS")
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
                .font(.title3.weight(.bold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Text(unit)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Theme.creamDim)
        }
        .frame(minWidth: 62, alignment: .trailing)
    }
}
