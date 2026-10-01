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
                    ScrollView(.horizontal, showsIndicators: true) {
                        grid
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

    private var title: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Shots").font(.of(.title3, weight: .bold))
                Text("THIS SESSION · \(table.rows.count)")
                    .font(.of(.caption, weight: .semibold))
                    .foregroundStyle(Theme.gold)
            }
            Spacer()
            Button("Done") { close() }
                .buttonStyle(.bordered)
                .accessibilityIdentifier(RangeTestTags.shared.TABLE_CLOSE)
        }
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

    private func line(_ cells: [String], header: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, text in
                Text(text)
                    .font(header ? .of(.caption, weight: .semibold) : .of(.subheadline).monospacedDigit())
                    .foregroundStyle(header ? Theme.creamDim : Theme.cream)
                    .lineLimit(1)
                    .frame(width: Self.width(of: index), alignment: index == Self.clubColumn ? .leading : .trailing)
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

    private static func width(of column: Int) -> CGFloat {
        switch column {
        case 0: 32
        case clubColumn: 80
        default: 68
        }
    }
}
