// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The top-down dispersion chart (Android's `DispersionCard`): distance arcs around the tee, one
/// dot per shot (club colour and short label; hollow when its side was estimated), an ellipse per
/// club and a ring on the selected shot. Framing, arcs, ellipses and tap hit-testing all come from
/// the shared `DispersionProjection`, so both platforms draw the same chart.
struct DispersionChartView: View {
    let dispersion: SessionDispersionUiState
    let units: UnitSystem
    let selectedId: String?
    let onSelect: (String?) -> Void

    private let chartHeight: CGFloat = 280
    private let dotRadius: CGFloat = 11
    private let tapRadius: CGFloat = 24
    private let ellipseSegments: Int32 = 64

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("DISPERSION")
                .font(.ofEyebrow)
                .tracking(1.4)
                .foregroundStyle(Theme.gold)
            GeometryReader { geometry in
                let projection = makeProjection(geometry.size)
                Canvas { context, size in
                    drawArcs(&context, size: size, projection: projection)
                    drawCentreLine(&context, size: size, projection: projection)
                    drawEllipses(&context, projection: projection)
                    drawDots(&context, projection: projection)
                }
                .contentShape(Rectangle())
                .onTapGesture(coordinateSpace: .local) { location in
                    let index = projection.nearest(
                        samples: dispersion.points.map(\.sample),
                        tapX: Double(location.x),
                        tapY: Double(location.y),
                        radius: Double(tapRadius)
                    )
                    onSelect(index.map { dispersion.points[Int($0.int32Value)].id })
                }
            }
            .frame(height: chartHeight)
            .clipped()
            .accessibilityElement()
            .accessibilityLabel(chartDescription)
            .accessibilityIdentifier("session.dispersion")
            if let spread = dispersion.clubSpread {
                Text(DispersionCopy.shared.spreadSummary(spread: spread, units: units))
                    .font(.of(.caption))
                    .accessibilityIdentifier("session.spread")
            }
            if dispersion.possibleBadReadCount > 0 {
                Text(DispersionCopy.shared.badReadCaption(count: dispersion.possibleBadReadCount))
                    .font(.of(.caption))
                    .foregroundStyle(Theme.warning)
            }
            if dispersion.estimatedSideCount > 0 {
                Text(estimatedCaption)
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
            }
        }
        .padding(.vertical, 4)
    }

    private var unitSuffix: String { units == .metric ? "m" : "y" }

    private var chartDescription: String {
        let clubs = dispersion.points.map { Units.clubLabel($0.club) }.uniqued().joined(separator: ", ")
        let count = dispersion.points.count
        let shots = count == 1 ? "1 shot" : "\(count) shots"
        return "Dispersion chart, \(shots): \(clubs). Select a shot in the list to see its details."
    }

    private var estimatedCaption: String {
        let count = dispersion.estimatedSideCount
        return count == 1
            ? "1 shot has no side data and sits on the centre line (hollow dot)."
            : "\(count) shots have no side data and sit on the centre line (hollow dots)."
    }

    private func makeProjection(_ size: CGSize) -> DispersionProjection {
        DispersionProjection(
            viewport: dispersion.viewport,
            width: Double(size.width),
            height: Double(size.height),
            maxStretch: DispersionProjection.companion.DEFAULT_MAX_STRETCH
        )
    }

    /// Distance arcs around the tee (ovals when offline is stretched), labelled at the right edge
    /// where the label fits inside the chart.
    private func drawArcs(_ context: inout GraphicsContext, size: CGSize, projection: DispersionProjection) {
        for arc in dispersion.viewport.arcs {
            let radii = projection.arcRadii(arc: arc)
            let radiusX = radii.first?.doubleValue ?? 0
            let radiusY = radii.second?.doubleValue ?? 0
            let oval = CGRect(
                x: projection.teeX - radiusX,
                y: projection.teeY - radiusY,
                width: radiusX * 2,
                height: radiusY * 2
            )
            context.stroke(Path(ellipseIn: oval), with: .color(Theme.creamMuted.opacity(0.7)), lineWidth: 1)

            let label = context.resolve(
                Text("\(arc.label)\(unitSuffix)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(Theme.creamDim)
            )
            let labelSize = label.measure(in: size)
            let labelX = size.width - labelSize.width - 4
            guard let arcY = projection.arcY(arc: arc, atX: Double(labelX + labelSize.width / 2))?.doubleValue else {
                continue
            }
            let top = CGFloat(arcY) - labelSize.height - 2
            if top >= 0 && top + labelSize.height <= size.height {
                context.draw(label, at: CGPoint(x: labelX, y: top), anchor: .topLeading)
            }
        }
    }

    private func drawCentreLine(_ context: inout GraphicsContext, size: CGSize, projection: DispersionProjection) {
        var line = Path()
        line.move(to: CGPoint(x: projection.teeX, y: 0))
        line.addLine(to: CGPoint(x: projection.teeX, y: Double(size.height)))
        context.stroke(line, with: .color(Theme.creamMuted.opacity(0.5)), style: StrokeStyle(lineWidth: 1, dash: [6, 6]))
    }

    private func drawEllipses(_ context: inout GraphicsContext, projection: DispersionProjection) {
        for club in dispersion.ellipses {
            let color = Theme.clubColor(Int(club.colorIndex))
            var path = Path()
            for (index, corner) in projection.ellipseOutline(ellipse: club.ellipse, segments: ellipseSegments).enumerated() {
                let point = CGPoint(x: corner.first?.doubleValue ?? 0, y: corner.second?.doubleValue ?? 0)
                if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            path.closeSubpath()
            context.fill(path, with: .color(color.opacity(0.08)))
            context.stroke(path, with: .color(color), lineWidth: 1.5)
        }
    }

    /// Oldest first, so the newest shot sits on top; the selected shot is drawn last of all.
    private func drawDots(_ context: inout GraphicsContext, projection: DispersionProjection) {
        let oldestFirst = Array(dispersion.points.reversed())
        let ordered = oldestFirst.filter { $0.id != selectedId } + oldestFirst.filter { $0.id == selectedId }
        for point in ordered {
            let color = Theme.clubColor(Int(point.colorIndex))
            let center = CGPoint(
                x: projection.x(offlineYards: point.offlineYards),
                y: projection.y(carryYards: point.carryYards)
            )
            let dot = CGRect(x: center.x - dotRadius, y: center.y - dotRadius, width: dotRadius * 2, height: dotRadius * 2)
            let labelColor: Color
            if point.sideEstimated {
                context.fill(Path(ellipseIn: dot), with: .color(Theme.bgDeep))
                context.stroke(Path(ellipseIn: dot.insetBy(dx: 1, dy: 1)), with: .color(color), lineWidth: 2)
                labelColor = color
            } else {
                context.fill(Path(ellipseIn: dot), with: .color(color))
                labelColor = Theme.bgDeep
            }
            context.draw(
                Text(point.shortLabel).font(.system(size: 9, weight: .bold)).foregroundColor(labelColor),
                at: center
            )
            var ringInset: CGFloat = -3
            if point.possibleBadRead {
                context.stroke(Path(ellipseIn: dot.insetBy(dx: ringInset, dy: ringInset)), with: .color(Theme.warning), lineWidth: 2.5)
                ringInset -= 5.5
            }
            if point.id == selectedId {
                context.stroke(Path(ellipseIn: dot.insetBy(dx: ringInset, dy: ringInset)), with: .color(.white), lineWidth: 2.5)
            }
        }
    }
}

/// The card for the shot selected on the chart or in the list (Android's `SelectedShotCardView`).
/// Delete removes the shot straight from here, like swiping its row. When editing is off
/// (`deletable` false: over Bluetooth, plan R8e) Delete stays visible but disabled; the actions
/// section says why.
struct SelectedShotCardView: View {
    let card: SelectedShotCard
    let units: UnitSystem
    var deletable: Bool = true
    let onClose: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Shot \(card.shotNumber) · \(card.clubName)")
                    .font(.of(.headline, weight: .semibold))
                    .lineLimit(2)
                Spacer()
                // 44 pt targets; the delete asks for confirmation first (plan R8f).
                Button(role: .destructive, action: onDelete) { targetLabel("Delete") }
                    .font(.of(.subheadline, weight: .semibold))
                    .tint(Theme.danger)
                    .buttonStyle(.borderless)
                    .disabled(!deletable)
                    .accessibilityIdentifier("session.selected.delete")
                Button(action: onClose) { targetLabel("Close") }
                    .font(.of(.subheadline, weight: .semibold))
                    .tint(Theme.gold)
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("session.selected.close")
            }
            if card.possibleBadRead {
                Text(DispersionCopy.shared.BAD_READ_NOTE)
                    .font(.of(.caption))
                    .foregroundStyle(Theme.warning)
                    .accessibilityIdentifier("session.selected.badRead")
            }
            HStack(alignment: .top) {
                metric("Carry", Units.distance(card.carryYards, units), Units.distanceUnit(units))
                metric("Spin", ShotFormat.number(card.spinRpm, decimals: 0), "rpm")
                metric("Club speed", Units.speed(card.clubSpeedMph, units), Units.speedUnit(units))
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session.selected")
    }

    /// A text button label padded out to a 44 pt target.
    private func targetLabel(_ text: String) -> some View {
        Text(text)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }

    private func metric(_ title: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.of(.subheadline))
                .foregroundStyle(Theme.creamDim)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(.of(.title3, weight: .bold).monospacedDigit())
                if value != ShotMetricFormatter.shared.MISSING {
                    Text(unit)
                        .font(.of(.caption))
                        .foregroundStyle(Theme.creamDim)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private extension Array where Element: Hashable {
    /// The elements in order, without repeats.
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
