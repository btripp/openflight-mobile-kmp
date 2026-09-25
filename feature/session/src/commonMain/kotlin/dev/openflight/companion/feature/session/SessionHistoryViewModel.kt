// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ShotHistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The session history list (plan R8h, the Expo app's Shots tab session list): every stored
 * session, newest first, with its date, shot count and first/last shot time.
 */
class SessionHistoryViewModel(
    private val history: ShotHistoryRepository,
) : ViewModel() {
    val uiState: StateFlow<SessionHistoryUiState> =
        combine(history.sessions(), history.currentSessionId, history.isPersistent) { sessions, current, persistent ->
            SessionHistoryUiState(
                loaded = true,
                sessions = sessions.map { it.toRow(current) },
                isPersistent = persistent,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionHistoryUiState(),
        )

    fun onEvent(event: SessionHistoryEvent) {
        when (event) {
            SessionHistoryEvent.ClearAll -> history.clearAll()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
