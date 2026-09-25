// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The range's metrics (reference `RangeMetricsOverlay.swift`): ball speed and carry on top; at the
/// bottom the club error, the estimated-flight badge and the detail metrics with the "NEXT CLUB"
/// selector, in one row in landscape or a two-column grid in portrait. While a ball flies and
/// through the landing dwell (the shared `compactMetrics`, plan R7b) the detail metrics fold into
/// one strip so the lower half of the scene, where the ball lands, stays visible.
struct RangeMetricsOverlay: View {
    let state: DrivingRangeUiState
    let isLandscape: Bool
    let onSelectClub: (GolfClub) -> Void

    private var shot: ShotEvent? { state.displayedShot }
    private var club: RangeClubState { state.club }

    var body: some View {
        VStack(spacing: 12) {
            primaryMetrics
            Spacer(minLength: 20)
            secondaryMetrics
        }
        .padding(.horizontal, isLandscape ? 28 : 16)
        .padding(.top, isLandscape ? 16 : 72)
        .padding(.bottom, 14)
    }

    private var primaryMetrics: some View {
        HStack(spacing: 12) {
            RangePrimaryMetric(
                title: "BALL SPEED",
                value: RangeFormat.number(shot.map { KotlinDouble(value: $0.ballSpeedMph) }, decimals: 1),
                unit: "MPH",
                accessibilityIdentifier: RangeTestTags.shared.BALL_SPEED
            )
            RangePrimaryMetric(
                title: "CARRY",
                value: RangeFormat.number(shot.map { KotlinDouble(value: $0.estimatedCarryYards) }, decimals: 0),
                unit: "YDS",
                accessibilityIdentifier: RangeTestTags.shared.CARRY
            )
        }
        .frame(maxWidth: isLandscape ? 540 : .infinity)
    }

    private var secondaryMetrics: some View {
        VStack(spacing: 8) {
            if let clubError = club.error {
                Label(clubError, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.danger)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.64), in: Capsule())
                    .accessibilityIdentifier(RangeTestTags.shared.CLUB_ERROR)
            }

            if DrivingRangeUiStateKt.usesEstimatedFlight(state) {
                Label("Estimated flight uses club defaults", systemImage: "wand.and.stars")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.5), in: Capsule())
                    .accessibilityIdentifier(RangeTestTags.shared.ESTIMATED)
            }

            if DrivingRangeUiStateKt.compactMetrics(state) {
                compactStrip
            } else {
                detailPanel
            }
        }
        .animation(.easeInOut(duration: 0.25), value: DrivingRangeUiStateKt.compactMetrics(state))
    }

    private var compactStrip: some View {
        Text(DrivingRangeUiStateKt.compactMetricsSummary(state))
            .font(.caption.weight(.semibold).monospacedDigit())
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(.black.opacity(0.55), in: Capsule())
            .overlay {
                Capsule().stroke(.white.opacity(0.14), lineWidth: 0.8)
            }
            .transition(.opacity)
            .accessibilityIdentifier(RangeTestTags.shared.METRICS_COMPACT)
    }

    @ViewBuilder
    private var detailPanel: some View {
        let metrics = detailMetrics
        Group {
            if isLandscape {
                HStack(spacing: 8) {
                    clubMetric
                    ForEach(metrics) { metric in
                        RangeDetailMetric(metric: metric)
                    }
                }
            } else {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 2),
                    spacing: 8
                ) {
                    clubMetric
                    ForEach(metrics) { metric in
                        RangeDetailMetric(metric: metric)
                    }
                }
            }
        }
        .transition(.opacity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(RangeTestTags.shared.METRICS_DETAIL)
    }

    private var detailMetrics: [RangeMetricValue] {
        [
            RangeMetricValue(title: "CLUB SPEED", value: RangeFormat.number(shot?.clubSpeedMph, decimals: 1), unit: "mph"),
            RangeMetricValue(title: "SMASH", value: RangeFormat.number(shot?.smashFactor, decimals: 2), unit: ""),
            RangeMetricValue(title: "LAUNCH", value: RangeFormat.number(shot?.launchAngleVertical, decimals: 1), unit: "°"),
            RangeMetricValue(
                title: "DIRECTION",
                value: RangeFormat.number(shot?.launchAngleHorizontal, decimals: 1, signed: true),
                unit: "°"
            ),
            RangeMetricValue(title: "SPIN", value: RangeFormat.number(shot?.spinRpm, decimals: 0), unit: "rpm"),
            RangeMetricValue(
                title: "PATH",
                value: RangeFormat.number(shot?.clubPathDeg, decimals: 1, signed: true),
                unit: "°"
            ),
            RangeMetricValue(
                title: "SPIN AXIS",
                value: RangeFormat.number(shot?.spinAxisDeg, decimals: 1, signed: true),
                unit: "°"
            ),
        ]
    }

    private var clubMetric: some View {
        ClubSelectionMenu(
            selectedClub: club.selected,
            // Connected (ContentView.swift:128) and no club change in flight (plan §0.3).
            isEnabled: club.selectionEnabled && !club.isChanging,
            onSelect: onSelectClub
        ) {
            HStack(spacing: 5) {
                VStack(spacing: 2) {
                    Text("NEXT CLUB")
                        .font(.system(size: 9, weight: .bold))
                        .tracking(0.8)
                        .foregroundStyle(.white.opacity(0.68))
                        .lineLimit(1)
                    Text(club.selected.displayName)
                        .font(.subheadline.weight(.bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)
                }
                .frame(maxWidth: .infinity)

                if club.isChanging {
                    ProgressView()
                        .controlSize(.small)
                        .tint(Theme.success)
                } else {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Theme.success)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 45)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(.black.opacity(0.58), in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Theme.success.opacity(0.48), lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Club for next shot, \(club.selected.displayName)")
        .accessibilityIdentifier(RangeTestTags.shared.CLUB_SELECTOR)
    }
}

/// The shared `ShotMetricFormatter` (en-US, "—" for a missing value), with the reference's `signed`.
enum RangeFormat {
    static func number(_ value: KotlinDouble?, decimals: Int32, signed: Bool = false) -> String {
        ShotMetricFormatter.shared.number(value: value, decimals: decimals, signed: signed)
    }
}

private struct RangeMetricValue: Identifiable {
    let title: String
    let value: String
    let unit: String

    var id: String { title }
    var isDegrees: Bool { unit == "°" }
    var isMissing: Bool { value == ShotMetricFormatter.shared.MISSING }
}

private struct RangePrimaryMetric: View {
    let title: String
    let value: String
    let unit: String
    let accessibilityIdentifier: String

    var body: some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.caption2.weight(.bold))
                .tracking(1.4)
                .foregroundStyle(.white.opacity(0.72))
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(size: 38, weight: .heavy, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.65)
                    .lineLimit(1)
                Text(unit)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white.opacity(0.72))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.black.opacity(0.48), in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(.white.opacity(0.18), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}

private struct RangeDetailMetric: View {
    let metric: RangeMetricValue

    var body: some View {
        VStack(spacing: 2) {
            Text(metric.title)
                .font(.system(size: 9, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.62))
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                // Degrees attach tight ("11.4°", like the shared formatDegrees); word units follow.
                Text(metric.isDegrees && !metric.isMissing ? metric.value + "°" : metric.value)
                    .font(.subheadline.weight(.bold).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                if !metric.unit.isEmpty, !metric.isDegrees, !metric.isMissing {
                    Text(metric.unit)
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.62))
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 45)
        .padding(.horizontal, 6)
        .padding(.vertical, 5)
        .background(.black.opacity(0.48), in: RoundedRectangle(cornerRadius: 12))
        .overlay {
            RoundedRectangle(cornerRadius: 12)
                .stroke(.white.opacity(0.13), lineWidth: 0.8)
        }
    }
}
