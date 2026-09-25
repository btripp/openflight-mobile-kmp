// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

// Small building blocks shared by the R5b/R6c screens, the SwiftUI counterparts of Android's
// `core:designsystem` wrappers (OfCard, OfConfidenceDots, OfDisabledReason, OfChip, OfPill ...).

/// A rounded card in the dashboard's style.
struct CardBackground: ViewModifier {
    var cornerRadius: CGFloat = 20

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(Theme.cream.opacity(0.07), lineWidth: 1)
            }
    }
}

extension View {
    func card(cornerRadius: CGFloat = 20) -> some View {
        modifier(CardBackground(cornerRadius: cornerRadius))
    }
}

/// The gold, tracked-out label above a card's content ("LATEST SHOT").
struct Eyebrow: View {
    let text: String
    var color: Color = Theme.gold

    init(_ text: String, color: Color = Theme.gold) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(.ofEyebrow)
            .tracking(1.7)
            .foregroundStyle(color)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The web UI's confidence badge (`ShotDisplay.tsx` `MetricCard`): up to three dots and a label.
/// "experimental" shows the label only.
struct ConfidenceDots: View {
    let level: ConfidenceLevel

    var body: some View {
        HStack(spacing: 4) {
            if level.showsDots {
                ForEach(0 ..< Int(ConfidenceLevel.companion.MAX_DOTS), id: \.self) { index in
                    Circle()
                        .fill(index < Int(level.filledDots) ? Theme.gold : Theme.cream.opacity(0.2))
                        .frame(width: 7, height: 7)
                }
            }
            Text(level.label)
                .font(.of(.caption, weight: .semibold))
                .foregroundStyle(level.showsDots ? Theme.gold : Theme.warning)
                .padding(.leading, level.showsDots ? 2 : 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Confidence \(level.label)")
    }
}

/// Why a Wi-Fi-only control is disabled ("Requires Wi-Fi", "Not connected").
struct DisabledReason: View {
    let reason: String

    var body: some View {
        Label(reason, systemImage: "wifi.slash")
            .font(.of(.footnote, weight: .medium))
            .foregroundStyle(Theme.warning)
            .accessibilityLabel("Unavailable: \(reason)")
    }
}

/// A status message with an icon and words, never colour alone (plan R8f; Android's `OfNotice`):
/// a spinner while busy, a check for success, a warning triangle otherwise. VoiceOver reads it as
/// one element and hears it announced when it appears or its title changes.
struct NoticeRow: View {
    enum Tone {
        case busy, success, warning, problem
    }

    let title: String
    var detail: String?
    let tone: Tone

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var color: Color {
        switch tone {
        case .busy, .warning: Theme.warning
        case .success: Theme.success
        case .problem: Theme.danger
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            switch tone {
            case .busy where reduceMotion:
                // A still symbol instead of a spinning one when Reduce Motion is on.
                Image(systemName: "hourglass").foregroundStyle(Theme.gold)
            case .busy:
                ProgressView().tint(Theme.gold)
            case .success:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(color)
            case .warning, .problem:
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(color)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.of(.subheadline, weight: .semibold))
                    .foregroundStyle(Theme.cream)
                if let detail {
                    Text(detail)
                        .font(.of(.caption))
                        .foregroundStyle(Theme.creamDim)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .onAppear { announce() }
        .onChange(of: title) { _, _ in announce() }
    }

    private func announce() {
        let text = [title, detail].compactMap { $0 }.joined(separator: ". ")
        AccessibilityNotification.Announcement(text).post()
    }
}

/// A selectable capsule with an optional count ("7-Iron 4").
struct ChipButton: View {
    let label: String
    var count: Int32?
    let isSelected: Bool
    var isEnabled = true
    var minTouchTarget = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text(label)
                    .font(.of(.subheadline, weight: .semibold))
                if let count {
                    Text("\(count)")
                        .font(.of(.caption, weight: .bold).monospacedDigit())
                        .foregroundStyle(isSelected ? Theme.bgDeep.opacity(0.7) : Theme.gold)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .foregroundStyle(isSelected ? Theme.bgDeep : Theme.cream)
            .background(isSelected ? Theme.gold : Theme.cream.opacity(0.06), in: Capsule())
            .overlay { Capsule().stroke(isSelected ? .clear : Theme.cream.opacity(0.14), lineWidth: 1) }
            // With `minTouchTarget`, a 44 pt tall touch target around the capsule; the capsule
            // itself keeps its size (plan R8f accessibility pass).
            .frame(minHeight: minTouchTarget ? 44 : nil)
            .contentShape(minTouchTarget ? AnyShape(Rectangle()) : AnyShape(Capsule()))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.45)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// A status pill with a colored dot ("● Connected").
struct StatusPill: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
                .font(.of(.footnote, weight: .semibold))
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.14), in: Capsule())
        .overlay { Capsule().stroke(color.opacity(0.4), lineWidth: 1) }
    }
}

/// Lays its children out left to right, wrapping onto new lines (the chip rows).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache _: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal _: ProposedViewSize, subviews: Subviews, cache _: inout ()) {
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var indices: [Int] = []
        var y: CGFloat = 0
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.indices.isEmpty {
                rows.append(current)
                current = Row(y: current.y + current.height + spacing)
            }
            current.width = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.indices.append(index)
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

/// A transient message at the bottom of a screen (a Pi error, "Shot not found" ...): the iOS
/// counterpart of Android's snackbar. It hides itself after a few seconds.
struct MessageBanner: ViewModifier {
    @Binding var message: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.of(.subheadline, weight: .medium))
                    .foregroundStyle(Theme.cream)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Theme.bgHover, in: RoundedRectangle(cornerRadius: 14))
                    .overlay { RoundedRectangle(cornerRadius: 14).stroke(Theme.gold.opacity(0.35), lineWidth: 1) }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .accessibilityIdentifier("message.banner")
                    .onTapGesture { self.message = nil }
                    .task(id: message) {
                        UIAccessibility.post(notification: .announcement, argument: message)
                        try? await Task.sleep(for: .seconds(4))
                        self.message = nil
                    }
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: message)
    }
}

extension View {
    func messageBanner(_ message: Binding<String?>) -> some View {
        modifier(MessageBanner(message: message))
    }
}

/// Display helpers over the shared `core:insights` formatting, so Swift renders exactly what
/// Android renders.
enum Units {
    static func speed(_ mph: Double, _ units: UnitSystem, decimals: Int32 = 1) -> String {
        ShotFormat.number(UnitSystemKt.convertSpeedFromMph(speedMph: mph, unitSystem: units), decimals: decimals)
    }

    static func speed(_ mph: KotlinDouble?, _ units: UnitSystem, decimals: Int32 = 1) -> String {
        guard let mph else { return ShotMetricFormatter.shared.MISSING }
        return speed(mph.doubleValue, units, decimals: decimals)
    }

    static func distance(_ yards: Double, _ units: UnitSystem, decimals: Int32 = 0) -> String {
        ShotFormat.number(
            UnitSystemKt.convertDistanceFromYards(distanceYards: yards, unitSystem: units),
            decimals: decimals
        )
    }

    static func distance(_ yards: KotlinDouble?, _ units: UnitSystem, decimals: Int32 = 0) -> String {
        guard let yards else { return ShotMetricFormatter.shared.MISSING }
        return distance(yards.doubleValue, units, decimals: decimals)
    }

    /// "62.3 mph" / "100.3 km/h".
    static func speedText(_ mph: Double, _ units: UnitSystem) -> String {
        UnitSystemKt.formatSpeed(speedMph: mph, unitSystem: units, digits: 1)
    }

    /// "9.5°", tight (the shared `formatDegrees`); "—" when missing.
    static func degrees(_ value: KotlinDouble?, decimals: Int32 = 1) -> String {
        guard let value else { return ShotMetricFormatter.shared.MISSING }
        return UnitSystemKt.formatDegrees(valueDegrees: value.doubleValue, digits: decimals)
    }

    static func speedUnit(_ units: UnitSystem) -> String { UnitSystemKt.speedUnitLabel(unitSystem: units) }
    static func distanceUnit(_ units: UnitSystem) -> String { UnitSystemKt.distanceUnitLabel(unitSystem: units) }

    /// A wire club value's display name ("7-iron" → "7-Iron"), or the raw value for an unknown club.
    static func clubLabel(_ wire: String) -> String {
        if let club = GolfClub.companion.fromWireValue(value: wire) { return club.displayName }
        return wire.isEmpty ? "Unknown" : wire
    }

    /// "19:42:10" from an ISO local timestamp ("2026-07-29T19:42:10.123456").
    static func clockTime(_ timestamp: String) -> String {
        guard let t = timestamp.firstIndex(of: "T") else { return timestamp }
        return String(timestamp[timestamp.index(after: t)...].prefix(8))
    }
}
