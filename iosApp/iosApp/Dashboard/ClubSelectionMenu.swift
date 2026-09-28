// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// The club menu, ported from the reference `ClubSelectionMenu.swift`; shared by the dashboard and
/// (step R3b) the driving range. `isEnabled` is the shared `ConnectionPanelState.clubMenuEnabled`
/// (connected and no club change in flight, plan §0.3).
///
/// Issue #15: `menu` (the shared `ClubMenu`) lists the active bag's clubs first, then the rest in a
/// native "All clubs" submenu. Without a bag it's all 20 clubs and no submenu.
struct ClubSelectionMenu<MenuLabel: View>: View {
    let menu: ClubMenu
    let selectedClub: GolfClub
    let isEnabled: Bool
    let onSelect: (GolfClub) -> Void
    @ViewBuilder let label: () -> MenuLabel

    var body: some View {
        Menu {
            clubButtons(menu.yourClubs)
            if menu.hasOtherClubs {
                Menu(ClubMenu.companion.ALL_CLUBS_LABEL) {
                    clubButtons(menu.otherClubs)
                }
            }
        } label: {
            label()
        }
        .disabled(!isEnabled)
    }

    private func clubButtons(_ clubs: [GolfClub]) -> some View {
        ForEach(clubs, id: \.self) { club in
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
    }
}
