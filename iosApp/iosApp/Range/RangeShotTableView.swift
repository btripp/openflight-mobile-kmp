// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Tester request 2026-09-30: the current session's shot table, Android's `RangeShotTablePane`.
/// On an iPhone it's a sheet over the scene; on an iPad a panel beside it, so shots keep flying.
/// Every cell is the shared `RangeShotTable`'s text, so both platforms show the same numbers. A
/// tapped row opens its shot on the range (and closes the sheet).
struct RangeShotTableView: View {
    let table: RangeShotTable
    let isPanel: Bool
    let send: (DrivingRangeEvent) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    /// The grid's columns widen with Dynamic Type up to XXXL (the table scrolls sideways), so
    /// the larger standard sizes don't truncate either; accessibility sizes use `stackedRows`.
    @ScaledMetric(relativeTo: .subheadline) private var numberWidth: CGFloat = 32
    @ScaledMetric(relativeTo: .subheadline) private var clubWidth: CGFloat = 80
    @ScaledMetric(relativeTo: .subheadline) private var metricWidth: CGFloat = 68

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            title
            if table.rows.isEmpty {
                Text("No shots in this session yet.")
                    .font(.of(.body))
                    .foregroundStyle(Theme.creamDim)
                Spacer(minLength: 0)
            } else {
                ScrollView(.vertical) {
                    if dynamicTypeSize.isAccessibilitySize {
                        stackedRows
                    } else {
                        ScrollView(.horizontal, showsIndicators: true) {
                            grid
                        }
                    }
                }
            }
        }
        .padding(isPanel ? 16 : 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.bgCard)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(RangeTestTags.shared.TABLE_PANEL)
    }

    /// At accessibility sizes Done goes under the title, so neither wraps a word per line.
    @ViewBuilder
    private var title: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                titleText
                doneButton
            }
        } else {
            HStack(alignment: .center) {
                titleText
                Spacer()
                doneButton
            }
        }
    }

    private var titleText: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Shots").font(.of(.title3, weight: .bold))
            Text("THIS SESSION · \(table.rows.count)")
                .font(.of(.caption, weight: .semibold))
                .foregroundStyle(Theme.gold)
        }
    }

    private var doneButton: some View {
        Button("Done") { close() }
            .buttonStyle(.bordered)
            .accessibilityIdentifier(RangeTestTags.shared.TABLE_CLOSE)
    }

    private var grid: some View {
        VStack(alignment: .leading, spacing: 0) {
            line(table.headers, header: true)
            ForEach(table.rows, id: \.id) { row in
                Button {
                    open(row.id)
                } label: {
                    line(row.cells, header: false)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("View on range")
                .accessibilityIdentifier(RangeTestTags.shared.tableRow(id: row.id))
            }
            if let average = table.average {
                line(average, header: true)
                    .background(Theme.bgHover, in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityIdentifier(RangeTestTags.shared.TABLE_AVERAGE)
            }
        }
    }

    /// Tester report (build 5, largest text): at accessibility sizes eleven columns can't share an
    /// iPhone's width (fixed widths truncated every number to "1…", and wider ones left two columns
    /// per screen), so each shot becomes a card of "header  value" lines with the same cells.
    private var stackedRows: some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            ForEach(table.rows, id: \.id) { row in
                Button {
                    open(row.id)
                } label: {
                    card(row.cells, highlighted: false)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("View on range")
                .accessibilityIdentifier(RangeTestTags.shared.tableRow(id: row.id))
            }
            if let average = table.average {
                card(average, highlighted: true)
                    .accessibilityIdentifier(RangeTestTags.shared.TABLE_AVERAGE)
            }
        }
    }

    /// "#3 Driver" over one line per measured column.
    private func card(_ cells: [String], highlighted: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(cells.prefix(Self.clubColumn + 1).joined(separator: "  "))
                .font(.of(.subheadline, weight: .semibold))
                .foregroundStyle(highlighted ? Theme.gold : Theme.cream)
            ForEach(Array(cells.enumerated()).dropFirst(Self.clubColumn + 1), id: \.offset) { index, text in
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline) {
                        cardHeader(index)
                        Spacer(minLength: 8)
                        cardValue(text)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        cardHeader(index)
                        cardValue(text)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(highlighted ? Theme.bgHover : Theme.bgDeep, in: RoundedRectangle(cornerRadius: 12))
    }

    private func cardHeader(_ index: Int) -> some View {
        Text(index < table.headers.count ? table.headers[index] : "")
            .font(.of(.caption, weight: .semibold))
            .foregroundStyle(Theme.creamDim)
    }

    private func cardValue(_ text: String) -> some View {
        Text(text)
            .font(.of(.subheadline).monospacedDigit())
            .foregroundStyle(Theme.cream)
            .lineLimit(1)
    }

    private func line(_ cells: [String], header: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, text in
                Text(text)
                    .font(header ? .of(.caption, weight: .semibold) : .of(.subheadline).monospacedDigit())
                    .foregroundStyle(header ? Theme.creamDim : Theme.cream)
                    .lineLimit(1)
                    .frame(width: width(of: index), alignment: index == Self.clubColumn ? .leading : .trailing)
                    .padding(.horizontal, 4)
            }
        }
        .padding(.vertical, 6)
    }

    private func open(_ shotId: String) {
        if !isPanel { close() }
        send(DrivingRangeEventLaunch(launch: RangeLaunch(sessionId: table.sessionId ?? "", shotId: shotId)))
    }

    private func close() {
        send(DrivingRangeEventShowTable(open: false))
    }

    /// The `RangeTableColumn` order: #, Club, then the measured columns.
    private static let clubColumn = 1

    private func width(of column: Int) -> CGFloat {
        switch column {
        case 0: numberWidth
        case Self.clubColumn: clubWidth
        default: metricWidth
        }
    }
}
