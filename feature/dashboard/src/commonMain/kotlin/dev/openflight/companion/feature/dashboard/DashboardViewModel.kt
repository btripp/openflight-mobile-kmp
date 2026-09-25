// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.computeClubChips
import dev.openflight.companion.core.insights.computeClubStats
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The dashboard's state holder (ContentView.swift). It reads [ShotRepository] and
 * [SettingsRepository], plus [PiSessionRepository]'s shot details to enrich shots on Wi-Fi (plan
 * R6b), and exposes one [DashboardUiState]; the only local state is the unsubmitted
 * host text and the in-flight club request.
 *
 * Starting and stopping the transport is the app shell's job (foreground/background), not this
 * ViewModel's, so the stream keeps running on every screen.
 */
class DashboardViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val piSession: PiSessionRepository,
    private val clubConfirmation: ClubConfirmation = ClubConfirmation(),
) : ViewModel() {
    /** The host field's text while the user edits it; `null` shows the saved host. */
    private val hostDraft = MutableStateFlow<String?>(null)
    private val clubRequest = MutableStateFlow(ClubRequest())

    private val savedSettings =
        combine(settings.transport, settings.host, settings.selectedClub, ::SavedSettings)

    /** The Socket.IO link as the card needs it: up, or refused for a denied Local Network. */
    private val piLink =
        piSession.linkState.map { link ->
            PiLinkFlags(
                link = link,
                connected = link == PiLinkState.Connected,
                localNetworkDenied = link is PiLinkState.Reconnecting && link.localNetworkDenied,
            )
        }

    private val prompts = combine(piLink, clubConfirmation.phase, ::Pair)

    private val panel =
        combine(savedSettings, shots.connectionState, hostDraft, clubRequest, prompts) {
            saved,
            state,
            draft,
            request,
            (link, confirmation),
            ->
            ConnectionPanelState(
                transport = saved.transport,
                hostText = draft ?: saved.host,
                state = state,
                club = saved.club,
                isChangingClub = request.inFlight,
                clubError = request.error,
                piLinkConnected = link.connected,
                localNetworkDenied =
                    link.localNetworkDenied ||
                        (state as? ConnectionState.Error)?.kind == ConnectionErrorKind.LOCAL_NETWORK_DENIED,
                showClubConfirmation = confirmation == ClubConfirmation.Phase.SHOWING,
                problem = ConnectionProblem.of(state, link.link),
            )
        }

    val uiState: StateFlow<DashboardUiState> =
        combine(
            panel,
            shots.history,
            settings.units,
            piSession.shotDetails,
            piSession.shotProcessing,
        ) { connection, history, units, details, processingState ->
            val stats = computeClubStats(history)
            val chips = computeClubChips(history)
            val latest = history.firstOrNull()
            val processing = ProcessingIndicator.of(processingState)
            if (latest == null) {
                DashboardUiState.Waiting(
                    connection,
                    units = units,
                    clubStats = stats,
                    clubChips = chips,
                    processing = processing,
                )
            } else {
                DashboardUiState.Live(
                    connection = connection,
                    latest = latest,
                    previous = history.drop(1),
                    units = units,
                    clubStats = stats,
                    clubChips = chips,
                    enrichments = enrichments(history, details),
                    processing = processing,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DashboardUiState.Waiting(ConnectionPanelState()),
        )

    private val newShotEffects = Channel<DashboardEffect>(Channel.BUFFERED)

    /**
     * One-shot signals for haptics and the shot-flash (plan R5a). Fires only when
     * [ShotRepository.history]'s newest shot changes to an id not seen before by this ViewModel:
     * never for the current value a fresh collector sees (already-present history, including a
     * `--preview-shot`), and never for a replayed shot, because [ShotRepository]'s
     * eventId-deduplicated history simply doesn't emit a new value for a duplicate (plan §0.3).
     */
    val effects: Flow<DashboardEffect> = newShotEffects.receiveAsFlow()

    init {
        // Plan R8d: the first connection of the launch (either link) opens the club confirmation.
        viewModelScope.launch {
            combine(shots.connectionState, piSession.linkState) { state, link ->
                state == ConnectionState.Connected || link == PiLinkState.Connected
            }.first { it }
            clubConfirmation.onConnected()
        }
        viewModelScope.launch {
            var lastEventId: String? = null
            var seenFirstHistory = false
            shots.history.collect { history ->
                val latestId = history.firstOrNull()?.eventId
                if (seenFirstHistory && latestId != null && latestId != lastEventId) {
                    newShotEffects.send(DashboardEffect.NewShot)
                }
                lastEventId = latestId
                seenFirstHistory = true
            }
        }
    }

    /**
     * The saved transport, or `null` until settings load, so the route never asks for a transport's
     * permissions on the default value before the saved one is known.
     */
    val selectedTransport: StateFlow<TransportType?> =
        settings.transport
            .map<TransportType, TransportType?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initialValue = null)

    fun onEvent(event: DashboardEvent) {
        when (event) {
            is DashboardEvent.TransportChanged -> {
                viewModelScope.launch { settings.setTransport(event.transport) }
            }

            is DashboardEvent.HostEdited -> {
                hostDraft.value = event.text
            }

            DashboardEvent.HostSubmitted -> {
                submitHost()
            }

            DashboardEvent.Retry -> {
                shots.retry()
            }

            is DashboardEvent.ClubSelected -> {
                // Picking a club answers the confirmation too.
                clubConfirmation.dismiss()
                changeClub(event.club)
            }

            DashboardEvent.DismissError -> {
                clubRequest.update { it.copy(error = null) }
            }

            is DashboardEvent.HostHintSelected -> {
                hostDraft.value = event.host
            }

            DashboardEvent.ClubConfirmed -> {
                clubConfirmation.dismiss()
            }
        }
    }

    /**
     * The reference retries on submit (ContentView.swift:212-214). A new host is persisted, which
     * makes the repository build a transport for it; an unchanged host just retries.
     */
    private fun submitHost() {
        viewModelScope.launch {
            val saved = settings.host.first()
            val submitted = (hostDraft.value ?: saved).trim()
            if (submitted == saved) {
                shots.retry()
            } else {
                settings.setHost(submitted)
            }
            hostDraft.value = null
        }
    }

    /** ContentView.swift `changeClub(to:)`: one request at a time; the repository persists the Pi's answer. */
    @Suppress("TooGenericExceptionCaught") // Any failure is shown under the menu, like the reference.
    private fun changeClub(club: GolfClub) {
        if (clubRequest.value.inFlight) return
        clubRequest.value = ClubRequest(inFlight = true)
        viewModelScope.launch {
            val error =
                try {
                    shots.setClub(club)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failure.message ?: CLUB_CHANGE_FAILED
                }
            clubRequest.value = ClubRequest(inFlight = false, error = error)
        }
    }

    /** Joins the history to the Pi's session rows on the timestamp (plan R6a/R6b: the only shared key). */
    private fun enrichments(
        history: List<ShotEvent>,
        details: Map<String, ShotDetail>,
    ): Map<String, ShotEnrichment> {
        if (details.isEmpty()) return emptyMap()
        return buildMap {
            for (shot in history) {
                details[shot.timestamp]?.let { put(shot.eventId, ShotEnrichment.from(it)) }
            }
        }
    }

    private data class SavedSettings(
        val transport: TransportType,
        val host: String,
        val club: GolfClub,
    )

    private data class PiLinkFlags(
        val link: PiLinkState,
        val connected: Boolean,
        val localNetworkDenied: Boolean,
    )

    private data class ClubRequest(
        val inFlight: Boolean = false,
        val error: String? = null,
    )

    companion object {
        const val CLUB_CHANGE_FAILED = "Couldn't change the club."
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
