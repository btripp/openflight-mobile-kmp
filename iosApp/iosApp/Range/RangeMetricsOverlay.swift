// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The range's metrics (reference `RangeMetricsOverlay.swift`): ball speed and carry on top; at the
/// bottom the Pi battery warning (issue #48), the club error, the estimated-flight badge and the
/// detail metrics with the "NEXT CLUB" selector, laid out by the shared `detailLayout` (plan
/// F8a2p, as on Android): one row in landscape, two compact rows of four over a portrait scene,
/// the roomy two-column grid docked to the side, and one strip while a ball flies and through the
/// landing dwell (the shared `compactMetrics`, plan R7b) so the landing area stays visible.
///
/// Plan F8a2p: over the scene the ball speed and carry cards are compact too, so the tee view keeps
/// most of the screen, and every card reports its frame as a range obstruction.
struct RangeMetricsOverlay: View {
    let state: DrivingRangeUiState
    let isLandscape: Bool
    /// Plan F1c: true in regular landscape (an iPad), where the metrics dock to one side instead
    /// of spanning the top and bottom of the scene.
    var docksToSide: Bool = false
    /// Plan F8d-B: the portrait top padding that keeps the metrics clear of the range controls,
    /// which take two rows when the status pill doesn't fit beside the buttons. At least 72 pt.
    var topInset: CGFloat = 72
    let onSelectClub: (GolfClub) -> Void

    private var shot: ShotEvent? { state.displayedShot }
    /// Plan F8f: the chosen units (the range quick settings and Settings › Practice share them).
    private var numbers: RangeNumbers { state.camera.numbers }
    private var layout: RangeDetailLayout {
        DrivingRangeUiStateKt.detailLayout(state, landscape: isLandscape, docked: docksToSide)
    }
    private var club: RangeClubState { state.club }

    var body: some View {
        if docksToSide {
            dockedLayout
        } else {
            spanningLayout
        }
    }

    private var spanningLayout: some View {
        VStack(spacing: 12) {
            primaryMetrics
            Spacer(minLength: 20)
            secondaryMetrics
        }
        .padding(.horizontal, isLandscape ? 28 : 16)
        .padding(.top, isLandscape ? 16 : max(72, topInset))
        .padding(.bottom, 14)
    }

    /// A side panel on the trailing edge, so the tee-to-landing area in the middle stays clear.
    private var dockedLayout: some View {
        HStack {
            Spacer(minLength: 0)
            VStack(spacing: 16) {
                primaryMetrics
                secondaryMetrics
            }
            .frame(width: 320)
            .padding(.vertical, 16)
            .padding(.horizontal, 16)
            .padding(.top, 44)
        }
    }

    private var primaryMetrics: some View {
        HStack(spacing: 12) {
            RangePrimaryMetric(
                title: "BALL SPEED",
                value: numbers.speed(mph: shot.map { KotlinDouble(value: $0.ballSpeedMph) }, decimals: 1),
                unit: numbers.speedUnit.uppercased(),
                compact: !docksToSide,
                accessibilityIdentifier: RangeTestTags.shared.BALL_SPEED
            )
            .rangeObstruction("ballSpeed")
            RangePrimaryMetric(
                title: "CARRY",
                value: numbers.distance(yards: shot.map { KotlinDouble(value: $0.estimatedCarryYards) }, decimals: 0),
                unit: numbers.distanceUnit.uppercased(),
                compact: !docksToSide,
                accessibilityIdentifier: RangeTestTags.shared.CARRY
            )
            .rangeObstruction("carry")
        }
        .frame(maxWidth: isLandscape ? 540 : .infinity)
    }

    private var secondaryMetrics: some View {
        VStack(spacing: 8) {
            if let warning = state.batteryWarning {
                // Issue #48: one compact pill with the other bottom pills, never over the flight.
                // The detail is for VoiceOver; the Dashboard's notice spells it out on screen.
                Label(warning.title, systemImage: "battery.25percent")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(warning.level == .critical ? Theme.danger : Theme.warning)
                    .lineLimit(1)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.64), in: Capsule())
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(warning.title). \(warning.detail)")
                    .accessibilityIdentifier(RangeTestTags.shared.BATTERY_WARNING)
                    .rangeObstruction("battery")
            }

            if let clubError = club.error {
                Label(clubError, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.danger)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.64), in: Capsule())
                    .accessibilityIdentifier(RangeTestTags.shared.CLUB_ERROR)
                    .rangeObstruction("clubError")
            }

            if DrivingRangeUiStateKt.usesEstimatedFlight(state) {
                Label("Estimated flight uses club defaults", systemImage: "wand.and.stars")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.horizontal, 11)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.5), in: Capsule())
                    .accessibilityIdentifier(RangeTestTags.shared.ESTIMATED)
                    .rangeObstruction("estimated")
            }

            if layout == .strip {
                compactStrip
                    .rangeObstruction("details")
            } else {
                detailPanel
                    .rangeObstruction("details")
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
        let spacing: CGFloat = layout == .grid ? 8 : 6
        Group {
            if layout == .row {
                HStack(spacing: 8) {
                    clubMetric
                    ForEach(metrics) { metric in
                        RangeDetailMetric(metric: metric)
                    }
                }
            } else {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: spacing), count: Int(layout.columns)),
                    spacing: spacing
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
            RangeMetricValue(
                title: "CLUB SPEED",
                value: numbers.speed(mph: shot?.clubSpeedMph, decimals: 1),
                unit: numbers.speedUnit
            ),
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
                        .minimumScaleFactor(0.7)
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
    /// Plan F8a2p: a shorter card over the scene.
    var compact = false
    let accessibilityIdentifier: String

    var body: some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.caption2.weight(.bold))
                .tracking(1.4)
                .foregroundStyle(.white.opacity(0.72))
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.system(size: compact ? 28 : 38, weight: .heavy, design: .rounded))
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
        .padding(.vertical, compact ? 6 : 10)
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
