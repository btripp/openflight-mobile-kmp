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
                            HStack(spacing: 8) {
                                Text(shot.timeLabel).font(.of(.caption)).foregroundStyle(Theme.creamDim)
                                Spacer()
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
