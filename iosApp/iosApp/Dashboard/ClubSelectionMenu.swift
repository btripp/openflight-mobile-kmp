// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The club menu, ported from the reference `ClubSelectionMenu.swift`; shared by the dashboard and
/// (step R3b) the driving range. `isEnabled` is the shared `ConnectionPanelState.clubMenuEnabled`
/// (connected and no club change in flight, plan §0.3).
struct ClubSelectionMenu<MenuLabel: View>: View {
    let selectedClub: GolfClub
    let isEnabled: Bool
    let onSelect: (GolfClub) -> Void
    @ViewBuilder let label: () -> MenuLabel

    var body: some View {
        Menu {
            ForEach(GolfClub.entries, id: \.self) { club in
                Button {
                    onSelect(club)
                } label: {
                    if club == selectedClub {
                        Label(club.displayName, systemImage: "checkmark")
                    } else {
                        Text(club.displayName)
                    }
                }
            }
        } label: {
            label()
        }
        .disabled(!isEnabled)
    }
}
