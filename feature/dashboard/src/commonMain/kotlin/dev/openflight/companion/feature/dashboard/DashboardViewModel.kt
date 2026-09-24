// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The dashboard's state holder (ContentView.swift). It reads [ShotRepository] and
 * [SettingsRepository] and exposes one [DashboardUiState]; the only local state is the unsubmitted
 * host text and the in-flight club request.
 *
 * Starting and stopping the transport is the app shell's job (foreground/background), not this
 * ViewModel's, so the stream keeps running on every screen.
 */
class DashboardViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    /** The host field's text while the user edits it; `null` shows the saved host. */
    private val hostDraft = MutableStateFlow<String?>(null)
    private val clubRequest = MutableStateFlow(ClubRequest())

    private val savedSettings =
        combine(settings.transport, settings.host, settings.selectedClub, ::SavedSettings)

    private val panel =
        combine(savedSettings, shots.connectionState, hostDraft, clubRequest) { saved, state, draft, request ->
            ConnectionPanelState(
                transport = saved.transport,
                hostText = draft ?: saved.host,
                state = state,
                club = saved.club,
                isChangingClub = request.inFlight,
                clubError = request.error,
            )
        }

    val uiState: StateFlow<DashboardUiState> =
        combine(panel, shots.history) { connection, history ->
            val latest = history.firstOrNull()
            if (latest == null) {
                DashboardUiState.Waiting(connection)
            } else {
                DashboardUiState.Live(connection, latest = latest, previous = history.drop(1))
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DashboardUiState.Waiting(ConnectionPanelState()),
        )

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
            is DashboardEvent.TransportChanged -> viewModelScope.launch { settings.setTransport(event.transport) }
            is DashboardEvent.HostEdited -> hostDraft.value = event.text
            DashboardEvent.HostSubmitted -> submitHost()
            DashboardEvent.Retry -> shots.retry()
            is DashboardEvent.ClubSelected -> changeClub(event.club)
            DashboardEvent.DismissError -> clubRequest.update { it.copy(error = null) }
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

    private data class SavedSettings(
        val transport: TransportType,
        val host: String,
        val club: GolfClub,
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
