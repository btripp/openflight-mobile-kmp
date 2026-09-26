// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Club Analysis (plan F5) over the shared `ClubAnalysisViewModel`: the bag's clubs ranked
/// longest first as bars scaled to the longest, a Lifetime / Last N sessions filter, a Carry /
/// Total (est.) toggle, and the gapping insights. On a regular-width window the insights sit next
/// to the bars. Android's `ClubAnalysisScreen.kt` renders the same state.
struct ClubAnalysisView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().clubAnalysisViewModel())

    var body: some View {
        ClubAnalysisContent(state: host.state, send: host.send)
            .navigationTitle("Club Analysis")
    }
}

/// The stateless analysis.
struct ClubAnalysisContent: View {
    let state: ClubAnalysisUiState
    let send: (ClubAnalysisEvent) -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        Group {
            if sizeClass == .regular {
                HStack(alignment: .top, spacing: 0) {
                    bars.frame(maxWidth: .infinity)
                    List { insights }
                        .listStyle(.insetGrouped)
                        .frame(maxWidth: 380)
                }
            } else {
                bars
            }
        }
        .screenBackground()
    }

    private var bars: some View {
        List {
            Section {
                Picker("Shots", selection: Binding(
                    get: { state.window },
                    set: { send(ClubAnalysisEventSelectWindow(window: $0)) }
                )) {
                    ForEach(state.windows, id: \.self) { window in
                        Text(window.label).tag(window)
                    }
                }
                .pickerStyle(.segmented)
                Picker("Distance", selection: Binding(
                    get: { state.metric },
                    set: { send(ClubAnalysisEventSelectMetric(metric: $0)) }
                )) {
                    ForEach(state.metrics, id: \.self) { metric in
                        Text(metric.label).tag(metric)
                    }
                }
                .pickerStyle(.segmented)
                if state.adjustedForConditions {
                    Text("Adjusted for conditions").font(.of(.caption)).foregroundStyle(Theme.creamMuted)
                }
            }
            .listRowBackground(Theme.bgCard)

            Section {
                if state.loaded && state.bars.isEmpty {
                    Text("No shots with your bag's clubs yet.")
                        .foregroundStyle(Theme.creamDim)
                        .accessibilityIdentifier(BagTestTags.shared.ANALYSIS_EMPTY)
                }
                ForEach(state.bars, id: \.wireValue) { bar in
                    NavigationLink {
                        ClubDetailView(wireValue: bar.wireValue)
                    } label: {
                        ClubBarView(bar: bar)
                    }
                    .accessibilityIdentifier(BagTestTags.shared.bar(wireValue: bar.wireValue))
                }
            }
            .listRowBackground(Theme.bgCard)

            if sizeClass != .regular { insights }
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier(BagTestTags.shared.ANALYSIS_LIST)
    }

    @ViewBuilder
    private var insights: some View {
        Section("Gapping") {
            if state.insightTexts.isEmpty {
                Text("No gapping issues found.").foregroundStyle(Theme.creamDim)
            }
            ForEach(state.insightTexts, id: \.self) { text in
                Text(text)
                    .font(.of(.subheadline))
                    .accessibilityIdentifier(BagTestTags.shared.INSIGHT)
            }
            Text(BagCopy.shared.HEURISTIC_NOTE).font(.of(.caption)).foregroundStyle(Theme.creamMuted)
            Text(BagCopy.shared.EXCLUDES_IMPORTED).font(.of(.caption)).foregroundStyle(Theme.creamMuted)
        }
        .listRowBackground(Theme.bgCard)
    }
}

/// One ranked bar, in the club's colour.
struct ClubBarView: View {
    let bar: ClubBarRow

    var body: some View {
        HStack(spacing: 10) {
            ClubBadge(shortLabel: bar.shortLabel, colorIndex: Int(bar.colorIndex))
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(bar.name).font(.of(.subheadline))
                    Spacer()
                    Text(bar.valueLabel)
                        .font(.of(.headline, weight: .bold).monospacedDigit())
                        .foregroundStyle(Theme.gold)
                    if bar.estimated { EstimatedBadge() }
                }
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 4).fill(Theme.bgHover)
                        RoundedRectangle(cornerRadius: 4)
                            .fill(Theme.clubColor(Int(bar.colorIndex)))
                            .frame(width: proxy.size.width * bar.fraction)
                    }
                }
                .frame(height: 12)
                Text([bar.plusMinusLabel, bar.shotCountLabel].compactMap { $0 }.joined(separator: " · "))
                    .font(.of(.caption2))
                    .foregroundStyle(Theme.creamMuted)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(bar.accessibilityLabel)
    }
}

/// The "est." badge on every estimated number (plan §0.2).
struct EstimatedBadge: View {
    var body: some View {
        StatusPill(text: BagCopy.shared.ESTIMATED_BADGE, color: Theme.neutral)
            .accessibilityIdentifier(BagTestTags.shared.ESTIMATED)
    }
}
