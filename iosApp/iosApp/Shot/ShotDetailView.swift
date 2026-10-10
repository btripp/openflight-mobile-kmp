// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// One shot in depth, as a sheet over the screen it was tapped on (the session list, a stored
/// session, a club's recent shots): its mini ball flight and shape, then every metric. Mirrors
/// Android's `ShotDetailSheet`; the state comes from the shared `ShotDetailBuilder`.
struct ShotDetailSheet: View {
    let state: ShotDetailState
    let onClose: () -> Void
    /// Opens the full driving range on this shot; hidden if nil or the shot has no flight.
    var onViewOnRange: (() -> Void)?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if let shape = state.shapeLabel {
                    ShapeChip(label: shape, detail: state.shapeDescription)
                }
                if let flight = state.flight {
                    ShotFlightView(
                        flight: flight,
                        accessibilityText: [state.shapeLabel, state.shapeDescription]
                            .compactMap { $0 }.joined(separator: ". ")
                    )
                }
                MetricsGrid(metrics: state.metrics)
                if !state.notes.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(state.notes, id: \.self) { note in
                            Text(note).font(.of(.caption)).foregroundStyle(Theme.creamDim)
                        }
                    }
                    .accessibilityIdentifier(ShotDetailTestTags.shared.NOTES)
                }
            }
            .padding(20)
        }
        .background(Theme.bgElevated.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier(ShotDetailTestTags.shared.SHEET)
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(state.title)
                    .font(.of(.title3, weight: .semibold))
                    .foregroundStyle(Theme.cream)
                    .lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier(ShotDetailTestTags.shared.TITLE)
                Text(state.subtitle).font(.of(.caption)).foregroundStyle(Theme.creamDim)
            }
            Spacer()
            if let onViewOnRange, state.flight != nil {
                Button("Range", action: onViewOnRange)
                    .font(.of(.subheadline, weight: .semibold))
                    .tint(Theme.gold)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier(ShotDetailTestTags.shared.VIEW_ON_RANGE)
            }
            Button("Close", action: onClose)
                .font(.of(.subheadline, weight: .semibold))
                .tint(Theme.gold)
                .frame(minHeight: 44)
                .accessibilityIdentifier(ShotDetailTestTags.shared.CLOSE)
        }
    }
}

/// The shot's shape ("Push fade") and what the ball did.
private struct ShapeChip: View {
    let label: String
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.of(.subheadline, weight: .semibold)).foregroundStyle(Theme.gold)
            if let detail {
                Text(detail).font(.of(.caption)).foregroundStyle(Theme.creamDim)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.bgCard, in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(ShotDetailTestTags.shared.SHAPE)
    }
}

/// The metrics in rows of three (four on a regular width, e.g. an iPad).
private struct MetricsGrid: View {
    let metrics: [ShotDetailMetric]
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        let columns = Array(
            repeating: GridItem(.flexible(), alignment: .topLeading),
            count: horizontalSizeClass == .regular ? 4 : 3
        )
        LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
            ForEach(metrics, id: \.label) { metric in
                VStack(alignment: .leading, spacing: 2) {
                    Text(metric.label.uppercased())
                        .font(.of(.caption2, weight: .semibold))
                        .foregroundStyle(Theme.creamDim)
                        .lineLimit(1)
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Text(metric.value).font(.of(.headline, weight: .semibold)).foregroundStyle(Theme.cream)
                        if !metric.unit.isEmpty {
                            Text(metric.unit).font(.of(.caption)).foregroundStyle(Theme.creamDim)
                        }
                    }
                    .lineLimit(1)
                    if metric.estimated {
                        Text("est.").font(.of(.caption2)).foregroundStyle(Theme.creamMuted)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(ShotDetailTestTags.shared.metric(label: metric.label))
            }
        }
    }
}

/// The mini ball flight: from behind the golfer (drag sideways to orbit), from the top or from the
/// side. It flies once when shown and again on a tap; with Reduce Motion it shows the landed
/// flight, still. The geometry is the shared `FlightPathProjector`'s, as on Android.
struct ShotFlightView: View {
    let flight: ShotFlightPath
    let accessibilityText: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var view: FlightView = .behind
    @State private var orbit: Float = 0
    @State private var dragStart: Float?
    @State private var startedAt = Date()

    private static let views: [FlightView] = [.behind, .top, .side]

    /// About three times faster than real time, kept between 1.2 and 2.5 s (as on Android).
    private var duration: Double {
        min(max(flight.flightTimeSeconds * 0.35, 1.2), 2.5)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("View", selection: $view) {
                ForEach(Self.views, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier(ShotDetailTestTags.shared.VIEW_PICKER)
            TimelineView(.animation(paused: reduceMotion)) { timeline in
                let progress = reduceMotion
                    ? 1
                    : min(1, timeline.date.timeIntervalSince(startedAt) / duration)
                Canvas { context, size in
                    draw(in: &context, size: size, progress: Float(progress))
                }
            }
            .aspectRatio(1.6, contentMode: .fit)
            .background(Theme.bgDeep, in: RoundedRectangle(cornerRadius: 12))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
            .gesture(orbitGesture)
            .onTapGesture { startedAt = Date() }
            .accessibilityElement()
            .accessibilityLabel(accessibilityText.isEmpty ? "Ball flight" : accessibilityText)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint("Replays the flight")
            .accessibilityIdentifier(ShotDetailTestTags.shared.FLIGHT)
            Text(view == .behind ? "Drag to orbit · tap to replay" : "Tap to replay")
                .font(.of(.caption))
                .foregroundStyle(Theme.creamMuted)
        }
    }

    private var orbitGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard view == .behind else { return }
                let start = dragStart ?? orbit
                dragStart = start
                let limit = FlightPathProjector.shared.MAX_ORBIT_DEGREES
                orbit = min(max(start + Float(value.translation.width) * 0.25, -limit), limit)
            }
            .onEnded { _ in dragStart = nil }
    }

    private func draw(in context: inout GraphicsContext, size: CGSize, progress: Float) {
        let projected = FlightPathProjector.shared.project(
            path: flight,
            view: view,
            width: Float(size.width),
            height: Float(size.height),
            orbitDegrees: orbit,
            padding: 20,
            minMarkerGap: FlightPathProjector.shared.DEFAULT_MIN_MARKER_GAP
        )
        for guide in projected.guides {
            context.stroke(line(guide), with: .color(Theme.bgHover), lineWidth: 1)
        }
        context.stroke(
            line(projected.targetLine),
            with: .color(Theme.creamMuted),
            style: StrokeStyle(lineWidth: 1, dash: [6, 6])
        )
        for marker in projected.markers {
            let at = point(marker.position)
            context.fill(dot(at, radius: 2), with: .color(Theme.creamMuted))
            context.draw(
                Text("\(marker.yards)").font(.system(size: 10)).foregroundColor(Theme.creamMuted),
                at: CGPoint(x: at.x + 4, y: at.y),
                anchor: .leading
            )
        }
        let path = projected.path
        let ball = point(projected.ballAt(progress: progress))
        var trail = Path()
        if let first = path.first { trail.move(to: point(first)) }
        let shown = Int(progress * Float(path.count - 1))
        for sample in path.prefix(shown + 1).dropFirst() { trail.addLine(to: point(sample)) }
        trail.addLine(to: ball)
        context.stroke(
            trail,
            with: .color(Theme.gold),
            style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
        )
        if progress >= 1, projected.roll.count == 2 {
            context.stroke(
                line(projected.roll),
                with: .color(Theme.goldDim),
                style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [4, 4])
            )
            context.fill(dot(point(projected.roll[1]), radius: 3), with: .color(Theme.goldDim))
        }
        if let tee = path.first { context.fill(dot(point(tee), radius: 3), with: .color(Theme.cream)) }
        context.fill(dot(ball, radius: 5), with: .color(Theme.cream))
    }

    private func point(_ screen: ScreenPoint) -> CGPoint {
        CGPoint(x: CGFloat(screen.x), y: CGFloat(screen.y))
    }

    private func line(_ points: [ScreenPoint]) -> Path {
        var path = Path()
        if let first = points.first { path.move(to: point(first)) }
        for next in points.dropFirst() { path.addLine(to: point(next)) }
        return path
    }

    private func dot(_ center: CGPoint, radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
    }
}

extension View {
    /// Presents [ShotDetailSheet] while `detail` is set; a swipe down or Close calls `onClose`.
    func shotDetailSheet(
        _ detail: ShotDetailState?,
        onClose: @escaping () -> Void,
        onViewOnRange: ((ShotDetailState) -> Void)? = nil
    ) -> some View {
        sheet(isPresented: Binding(get: { detail != nil }, set: { if !$0 { onClose() } })) {
            if let detail {
                ShotDetailSheet(
                    state: detail,
                    onClose: onClose,
                    onViewOnRange: onViewOnRange.map { open in { open(detail) } }
                )
            }
        }
    }
}
