// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent

/**
 * What the dashboard renders. The connection card ([connection]) is on screen in every state;
 * the rest depends on whether a shot has arrived yet (ContentView.swift: `shotCard` +
 * `shotHistoryCard` versus `emptyState`).
 *
 * [units], [clubStats] and [clubChips] are the web-UI-parity additions from plan R5a: the unit
 * preference, the session stats and the per-club shot counts, both computed over the full
 * history (`core:insights`'s `computeClubStats`/`computeClubChips`). They default so every
 * existing call site (the Compose preview, VM tests) stays source-compatible.
 */
sealed interface DashboardUiState {
    val connection: ConnectionPanelState
    val units: UnitSystem
    val clubStats: ClubStats
    val clubChips: List<ClubChip>

    /** What the Pi is doing with the last swing (plan R8f), or `null`. */
    val processing: ProcessingIndicator?

    /** No shot yet: "Waiting for a shot". */
    data class Waiting(
        override val connection: ConnectionPanelState,
        override val units: UnitSystem = SettingsRepository.DEFAULT_UNITS,
        override val clubStats: ClubStats = ClubStats.EMPTY,
        override val clubChips: List<ClubChip> = emptyList(),
        override val processing: ProcessingIndicator? = null,
    ) : DashboardUiState

    /**
     * At least one shot: the latest shot card, plus the previous shots (newest first) when there
     * are any.
     *
     * @property enrichments the Pi's Socket.IO detail (plan R6b) for [latest] and [previous], keyed
     *   by [ShotEvent.eventId]: confidence badges, carry range, spin-adjusted carry, spin source and
     *   player. A shot without one (Bluetooth, no Socket.IO link, or a shot the Pi never reported)
     *   has no entry; read it with [enrichmentFor] or [latestEnrichment].
     */
    data class Live(
        override val connection: ConnectionPanelState,
        val latest: ShotEvent,
        val previous: List<ShotEvent>,
        override val units: UnitSystem = SettingsRepository.DEFAULT_UNITS,
        override val clubStats: ClubStats = ClubStats.EMPTY,
        override val clubChips: List<ClubChip> = emptyList(),
        val enrichments: Map<String, ShotEnrichment> = emptyMap(),
        override val processing: ProcessingIndicator? = null,
    ) : DashboardUiState {
        val latestEnrichment: ShotEnrichment? get() = enrichments[latest.eventId]

        fun enrichmentFor(shot: ShotEvent): ShotEnrichment? = enrichments[shot.eventId]
    }
}

/** A one-shot signal for haptics and the shot-flash (plan R5a), never re-delivered for a replayed shot. */
sealed interface DashboardEffect {
    data object NewShot : DashboardEffect
}

/**
 * The connection card: transport picker, status row, Wi-Fi host field and the "Club for next shot"
 * menu (ContentView.swift `connectionCard` and `clubSelector`).
 *
 * @property hostText the host field's text: the user's unsubmitted edit, or else the saved host.
 * @property isChangingClub a `set_club` request is in flight.
 * @property clubError the last club request's failure, shown under the menu until dismissed or retried.
 * @property piLinkConnected the Pi's Socket.IO link is up (a Pi without the SSE stream still
 *   counts as reachable for the help link and the club confirmation).
 * @property localNetworkDenied iOS refused the connection because Local Network access is off
 *   (plan R8d): the card offers to open Settings instead of only Retry.
 * @property showClubConfirmation the once-per-launch "is this the right club?" prompt (plan R8d).
 * @property problem why the phone can't reach the Pi, in words (plan R8f), or `null`.
 */
data class ConnectionPanelState(
    val transport: TransportType = SettingsRepository.DEFAULT_TRANSPORT,
    val hostText: String = SettingsRepository.DEFAULT_HOST,
    val state: ConnectionState = ConnectionState.Idle,
    val club: GolfClub = SettingsRepository.DEFAULT_CLUB,
    val isChangingClub: Boolean = false,
    val clubError: String? = null,
    val piLinkConnected: Boolean = false,
    val localNetworkDenied: Boolean = false,
    val showClubConfirmation: Boolean = false,
    val problem: ConnectionProblem? = null,
) {
    /**
     * The problem the card spells out. A Local Network denial has its own block with the way out
     * ([localNetworkDenied]), so it isn't repeated here.
     */
    val visibleProblem: ConnectionProblem?
        get() = problem?.takeIf { it.kind != ConnectionProblem.Kind.LOCAL_NETWORK_DENIED }

    /** Tap-to-fill suggestions under the host field (Wi-Fi only). */
    val hostHints: List<HostHint>
        get() = if (showHostField) HOST_HINTS else emptyList()

    /**
     * The docs link on the card (Expo `openflight-docs-link`): the build guide while nothing is
     * connected, the troubleshooting guide after a failed attempt, none once connected.
     */
    val helpLink: ConnectionHelpLink?
        get() =
            when {
                state == ConnectionState.Connected || piLinkConnected -> null
                state is ConnectionState.Error -> ConnectionHelpLink.TROUBLESHOOTING
                else -> ConnectionHelpLink.BUILD_GUIDE
            }

    /**
     * The club menu is enabled only while connected and not already changing clubs (plan §0.3,
     * ContentView.swift:282).
     */
    val clubMenuEnabled: Boolean
        get() = state == ConnectionState.Connected && !isChangingClub

    /** "OpenFlight Pi" once connected, otherwise the transport's name. */
    val statusTitle: String
        get() = if (state == ConnectionState.Connected) CONNECTED_TITLE else transport.label

    /** Retry replaces the spinner whenever the state allows it (`ConnectionState.canRetry`). */
    val showRetry: Boolean
        get() = state.canRetry

    /** A spinner shows while working towards a connection. */
    val showProgress: Boolean
        get() = !state.canRetry && state != ConnectionState.Connected

    val showHostField: Boolean
        get() = transport == TransportType.WIFI

    companion object {
        const val CONNECTED_TITLE = "OpenFlight Pi"

        /** Plan R8d: the Pi's own access point, then a typical home-router address. */
        val HOST_HINTS: List<HostHint> =
            listOf(
                HostHint(host = "192.168.4.1:8080", label = "Pi access point"),
                HostHint(host = "192.168.1.100:8080", label = "Home network"),
            )
    }
}

/** A tap-to-fill host suggestion: [host] goes into the field, [label] says what it is. */
data class HostHint(
    val host: String,
    val label: String,
)

/** The connection card's documentation link (plan R8d, Expo `ConnectionBar`). */
enum class ConnectionHelpLink(
    val label: String,
    val url: String,
) {
    BUILD_GUIDE("New to OpenFlight? Build one", "https://open-flight.github.io/openflight/get-started/"),
    TROUBLESHOOTING(
        "Can't connect? Troubleshooting guide",
        "https://open-flight.github.io/openflight/troubleshooting/",
    ),
}

/** The transport picker's label, matching the reference's `ShotTransport.label`. */
val TransportType.label: String
    get() =
        when (this) {
            TransportType.BLUETOOTH -> "Bluetooth"
            TransportType.WIFI -> "Wi-Fi"
        }
