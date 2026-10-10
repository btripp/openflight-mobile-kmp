// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// One club in depth (plan F5) over the shared `ClubDetailViewModel`: summary, carry histogram,
/// dispersion ellipse and recent shots. Android's `ClubDetailScreen.kt` renders the same state.
struct ClubDetailView: View {
    @StateObject private var host: ViewModelHost<ClubDetailViewModel>

    init(wireValue: String) {
        _host = StateObject(wrappedValue: ViewModelHost(KoinHelper().clubDetailViewModel(wireValue: wireValue)))
    }

    var body: some View {
        ClubDetailContent(state: host.state, send: host.send)
            .navigationTitle(host.state.clubName)
    }
}

/// The stateless club detail.
struct ClubDetailContent: View {
    let state: ClubDetailUiState
    let send: (ClubDetailEvent) -> Void

    /// Plan F8d-B: the recent shots' "View on range"; nil (no app shell) hides it.
    @Environment(\.openRange) private var openRange

    var body: some View {
        List {
            Section {
                Picker("Shots", selection: Binding(
                    get: { state.window },
                    set: { send(ClubDetailEventSelectWindow(window: $0)) }
                )) {
                    ForEach(state.windows, id: \.self) { window in
                        Text(window.label).tag(window)
                    }
                }
                .pickerStyle(.segmented)
            }
            .listRowBackground(Theme.bgCard)
            if state.loaded && state.shotCount == 0 {
                Section {
                    Text("No shots with this club yet. Hit a few with it selected and they'll show here.")
                        .foregroundStyle(Theme.creamDim)
                        .accessibilityIdentifier(BagTestTags.shared.DETAIL_EMPTY)
                }
                .listRowBackground(Theme.bgCard)
            } else {
                Section {
                    ForEach(Array(state.summaryLines.enumerated()), id: \.offset) { index, line in
                        Text(line)
                            .font(index == 0 ? .of(.headline, weight: .bold) : .of(.subheadline))
                            .foregroundStyle(index == 0 ? Theme.gold : Theme.creamDim)
                    }
                    if let total = state.totalLabel {
                        HStack { Text(total); EstimatedBadge() }
                    }
                    if let excluded = state.excludedLabel {
                        Text(excluded).foregroundStyle(Theme.warning).font(.of(.subheadline))
                    }
                    if state.adjustedForConditions {
                        Text("Adjusted for conditions").font(.of(.caption)).foregroundStyle(Theme.creamMuted)
                    }
                }
                .accessibilityIdentifier(BagTestTags.shared.DETAIL_SUMMARY)
                .listRowBackground(Theme.bgCard)

                if !state.histogram.isEmpty {
                    Section("Carry distribution") {
                        HistogramView(bins: state.histogram)
                            .frame(height: 100)
                            .accessibilityIdentifier(BagTestTags.shared.HISTOGRAM)
                    }
                    .listRowBackground(Theme.bgCard)
                }

                if let pattern = state.shapePattern {
                    Section("Shot shape") {
                        ShapePatternView(pattern: pattern)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(pattern.accessibilitySummary)
                            .accessibilityIdentifier(BagTestTags.shared.SHAPE_PATTERN)
                    }
                    .listRowBackground(Theme.bgCard)
                }

                if let dispersion = state.dispersion {
                    Section("Where it lands") {
                        ClubDispersionView(dispersion: dispersion)
                            .frame(height: 220)
                            .accessibilityElement()
                            .accessibilityLabel(dispersion.accessibilitySummary)
                            .accessibilityIdentifier(BagTestTags.shared.DISPERSION)
                    }
                    .listRowBackground(Theme.bgCard)
                }

                if !state.recentShots.isEmpty {
                    Section("Recent shots") {
                        ForEach(state.recentShots, id: \.id) { shot in
                            // Two lines (when and its shape, then the numbers), so nothing wraps
                            // or truncates on an iPhone (matches Android's RecentShot).
                            HStack(spacing: 8) {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack(spacing: 8) {
                                        Text(shot.timeLabel).font(.of(.caption)).foregroundStyle(Theme.creamDim)
                                        if let shape = shot.shapeLabel {
                                            Text(shape).font(.of(.caption, weight: .semibold)).foregroundStyle(Theme.cream)
                                        }
                                    }
                                    .lineLimit(1)
                                    HStack(spacing: 8) {
                                        Text(shot.carryLabel).foregroundStyle(Theme.gold).monospacedDigit()
                                        if let total = shot.totalLabel {
                                            Text(total).font(.of(.caption)).monospacedDigit()
                                            EstimatedBadge()
                                        }
                                        if let side = shot.sideLabel {
                                            Text(side).font(.of(.caption)).foregroundStyle(Theme.creamDim)
                                        }
                                        if shot.possibleBadRead {
                                            StatusPill(text: "Bad read?", color: Theme.danger)
                                        }
                                    }
                                    .lineLimit(1)
                                    .fixedSize(horizontal: false, vertical: true)
                                }
                                Spacer(minLength: 0)
                                if let openRange {
                                    ViewOnRangeButton(
                                        iconOnly: true,
                                        identifier: BagTestTags.shared.viewOnRange(shotId: shot.id)
                                    ) {
                                        openRange(RangeTarget(sessionId: shot.sessionId, shotId: String(shot.id)))
                                    }
                                }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { send(ClubDetailEventOpenShotDetail(id: shot.id)) }
                            .accessibilityElement(children: .contain)
                            .accessibilityAction(named: "Show details") {
                                send(ClubDetailEventOpenShotDetail(id: shot.id))
                            }
                            .accessibilityIdentifier(BagTestTags.shared.RECENT)
                        }
                    }
                    .listRowBackground(Theme.bgCard)
                }
            }
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(BagTestTags.shared.DETAIL)
        .screenBackground()
        .shotDetailSheet(
            state.shotDetail,
            onClose: { send(ClubDetailEventCloseShotDetail.shared) },
            onViewOnRange: openRange.map { open in
                { detail in
                    if let shot = state.recentShots.first(where: { String($0.id) == detail.id }) {
                        open(RangeTarget(sessionId: shot.sessionId, shotId: detail.id))
                    }
                }
            }
        )
    }
}

/// The carry histogram: one bar per 5-yard (or 5-metre) bin, empty bins kept.
struct HistogramView: View {
    let bins: [HistogramBin]

    var body: some View {
        VStack(spacing: 4) {
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(Array(bins.enumerated()), id: \.offset) { _, bin in
                    GeometryReader { proxy in
                        VStack {
                            Spacer(minLength: 0)
                            RoundedRectangle(cornerRadius: 3)
                                .fill(bin.count == 0 ? Theme.bgHover : Theme.gold)
                                .frame(height: max(proxy.size.height * bin.fraction, 3))
                        }
                    }
                }
            }
            HStack {
                Text(bins.first?.label.components(separatedBy: "–").first ?? "")
                Spacer()
                Text(bins.last?.label.components(separatedBy: "–").last ?? "")
            }
            .font(.of(.caption2))
            .foregroundStyle(Theme.creamMuted)
        }
        .accessibilityElement()
        .accessibilityLabel(
            "Carry distribution: " + bins.filter { $0.count > 0 }.map { "\($0.label), \($0.count) shots" }.joined(separator: "; ")
        )
    }
}

/// The club's landing spots and 68% ellipse, drawn with the shared `DispersionProjection`.
struct ClubDispersionView: View {
    let dispersion: ClubDispersionState

    var body: some View {
        Canvas { context, size in
            let projection = DispersionProjection(
                viewport: dispersion.viewport,
                width: Double(size.width),
                height: Double(size.height),
                maxStretch: DispersionProjection.companion.DEFAULT_MAX_STRETCH
            )
            var line = Path()
            line.move(to: CGPoint(x: projection.teeX, y: 0))
            line.addLine(to: CGPoint(x: projection.teeX, y: Double(size.height)))
            context.stroke(line, with: .color(Theme.creamMuted.opacity(0.5)), style: StrokeStyle(lineWidth: 1, dash: [6, 6]))
            for arc in dispersion.viewport.arcs {
                let radii = projection.arcRadii(arc: arc)
                let radiusX = radii.first?.doubleValue ?? 0
                let radiusY = radii.second?.doubleValue ?? 0
                let oval = CGRect(x: projection.teeX - radiusX, y: projection.teeY - radiusY, width: radiusX * 2, height: radiusY * 2)
                context.stroke(Path(ellipseIn: oval), with: .color(Theme.creamMuted.opacity(0.5)), lineWidth: 1)
            }
            if let ellipse = dispersion.ellipse {
                var path = Path()
                for (index, corner) in projection.ellipseOutline(ellipse: ellipse, segments: 64).enumerated() {
                    let point = CGPoint(x: corner.first?.doubleValue ?? 0, y: corner.second?.doubleValue ?? 0)
                    if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
                path.closeSubpath()
                context.fill(path, with: .color(Theme.gold.opacity(0.15)))
                context.stroke(path, with: .color(Theme.gold), lineWidth: 1.5)
            }
            for sample in dispersion.samples {
                let center = CGPoint(x: projection.x(offlineYards: sample.offlineYards), y: projection.y(carryYards: sample.carryYards))
                context.fill(Path(ellipseIn: CGRect(x: center.x - 4, y: center.y - 4, width: 8, height: 8)), with: .color(Theme.cream))
            }
        }
    }
}

/// The club's shot shape at a glance: its typical shape, how many shots went each way, and its
/// recent flights from above (the newest brightest). Mirrors Android's `ShapePatternCard`; the
/// geometry is the shared `FlightPathProjector.projectPattern`.
struct ShapePatternView: View {
    let pattern: ShapePatternState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(pattern.headline).font(.of(.title3, weight: .semibold)).foregroundStyle(Theme.gold)
            Text(pattern.talliesLabel).font(.of(.caption)).foregroundStyle(Theme.creamDim)
            if !pattern.flights.isEmpty {
                Canvas { context, size in draw(in: &context, size: size) }
                    .frame(height: 200)
                Text("Last \(pattern.flights.count) from above, newest brightest")
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamMuted)
            }
        }
        .padding(.vertical, 4)
    }

    private func draw(in context: inout GraphicsContext, size: CGSize) {
        guard let projected = FlightPathProjector.shared.projectPattern(
            paths: pattern.flights,
            width: Float(size.width),
            height: Float(size.height),
            padding: 12
        ) else { return }
        for guide in projected.guides {
            context.stroke(line(guide), with: .color(Theme.bgHover), lineWidth: 1)
        }
        context.stroke(line(projected.targetLine), with: .color(Theme.creamMuted), style: StrokeStyle(lineWidth: 1, dash: [6, 6]))
        // Oldest first, so the newest flight is drawn on top.
        for (index, flight) in projected.flights.enumerated().reversed() {
            let newest = index == 0
            let color = newest ? Theme.gold : Theme.goldDim.opacity(0.55)
            context.stroke(line(flight), with: .color(color), style: StrokeStyle(lineWidth: newest ? 3 : 2, lineCap: .round))
            if let last = flight.last {
                let center = CGPoint(x: CGFloat(last.x), y: CGFloat(last.y))
                context.fill(Path(ellipseIn: CGRect(x: center.x - 3, y: center.y - 3, width: 6, height: 6)), with: .color(color))
            }
        }
    }

    private func line(_ points: [ScreenPoint]) -> Path {
        var path = Path()
        if let first = points.first { path.move(to: CGPoint(x: CGFloat(first.x), y: CGFloat(first.y))) }
        for next in points.dropFirst() { path.addLine(to: CGPoint(x: CGFloat(next.x), y: CGFloat(next.y))) }
        return path
    }
}
