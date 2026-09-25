// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ShotHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The session history list (plan R8h, the Expo app's Shots tab session list): every stored
 * session, newest first, with its day, shot count, first/last shot time and how it was recorded.
 *
 * Plan R8f: "Clear all history" asks first, then shows pending until the stored sessions are gone
 * (the repository writes in the background), then done; storage that never reflects it fails after
 * [HISTORY_TIMEOUT_MILLIS] with a retry.
 */
@OptIn(ExperimentalTime::class)
class SessionHistoryViewModel(
    private val history: ShotHistoryRepository,
    private val now: () -> String = { Clock.System.now().toString() },
) : ViewModel() {
    private val action = MutableStateFlow<SessionActionState>(SessionActionState.Idle)
    private var actionJob: Job? = null

    val uiState: StateFlow<SessionHistoryUiState> =
        combine(history.sessions(), history.currentSessionId, history.isPersistent, action) {
            sessions,
            current,
            persistent,
            action,
            ->
            val year = now().take(YEAR_LENGTH).toIntOrNull()
            SessionHistoryUiState(
                loaded = true,
                sessions = sessions.map { it.toRow(current, year) },
                isPersistent = persistent,
                action = action,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionHistoryUiState(),
        )

    fun onEvent(event: SessionHistoryEvent) {
        when (event) {
            SessionHistoryEvent.ClearAll -> {
                if (!action.value.isBusy) action.value = SessionActionCopy.confirmClearAll
            }

            SessionHistoryEvent.ConfirmAction -> {
                if (action.value is SessionActionState.Confirming) clearAll()
            }

            SessionHistoryEvent.CancelAction -> {
                if (action.value is SessionActionState.Confirming) action.value = SessionActionState.Idle
            }

            SessionHistoryEvent.RetryAction -> {
                if ((action.value as? SessionActionState.Failed)?.canRetry == true) clearAll()
            }

            SessionHistoryEvent.DismissAction -> {
                if (action.value is SessionActionState.Done || action.value is SessionActionState.Failed) {
                    action.value = SessionActionState.Idle
                }
            }
        }
    }

    /** Done once none of the sessions listed when it was confirmed is left. */
    private fun clearAll() {
        val target = SessionAction.ClearAllHistory
        val cleared =
            uiState.value.sessions
                .map { it.id }
                .toSet()
        action.value = SessionActionState.Pending(target, SessionActionCopy.pending(target))
        history.clearAll()
        actionJob?.cancel()
        actionJob =
            viewModelScope.launch {
                val gone =
                    withTimeoutOrNull(HISTORY_TIMEOUT_MILLIS) {
                        history.sessions().first { sessions -> sessions.none { it.id in cleared } }
                    }
                action.value =
                    if (gone != null) {
                        SessionActionState.Done(target, SessionActionCopy.done(target))
                    } else {
                        SessionActionState.Failed(
                            action = target,
                            title = SessionActionCopy.failedTitle(target),
                            message = SessionActionCopy.STORAGE_DID_NOT_RESPOND,
                            canRetry = true,
                        )
                    }
            }
    }

    companion object {
        /** How long the history store may take to reflect a local delete or clear. */
        const val HISTORY_TIMEOUT_MILLIS: Long = 10_000
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val YEAR_LENGTH = 4
    }
}
