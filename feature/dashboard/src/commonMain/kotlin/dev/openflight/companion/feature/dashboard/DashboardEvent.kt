// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.GolfClub

/** User intents from the dashboard, sent up to [DashboardViewModel.onEvent]. */
sealed interface DashboardEvent {
    /** The transport picker changed; persisted, which switches the active transport. */
    data class TransportChanged(
        val transport: TransportType,
    ) : DashboardEvent

    /** A keystroke in the Wi-Fi host field. Local only: nothing reconnects until [HostSubmitted]. */
    data class HostEdited(
        val text: String,
    ) : DashboardEvent

    /** The host field's Go/Done action: persist the host (reconnecting to it), or retry when unchanged. */
    data object HostSubmitted : DashboardEvent

    data object Retry : DashboardEvent

    data class ClubSelected(
        val club: GolfClub,
    ) : DashboardEvent

    /** Clears the club error under the menu. */
    data object DismissError : DashboardEvent

    /** A host hint was tapped: fills the field (like typing it); nothing connects until [HostSubmitted]. */
    data class HostHintSelected(
        val host: String,
    ) : DashboardEvent

    /** "Looks right" on the once-per-launch club confirmation; it won't show again this launch. */
    data object ClubConfirmed : DashboardEvent

    /**
     * Plan R8f: the platform's answer about its local-network permission (Android 17's
     * `ACCESS_LOCAL_NETWORK`, which the shell asks for on Wi-Fi). A denial can't show up as an
     * error of its own (connections just time out), so the shell reports it and the card offers
     * the app's settings, like iOS's Local Network denial.
     */
    data class LocalNetworkPermissionChanged(
        val granted: Boolean,
    ) : DashboardEvent
}
