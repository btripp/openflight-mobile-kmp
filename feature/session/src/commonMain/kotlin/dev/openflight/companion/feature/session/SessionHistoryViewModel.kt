// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.insights.ExportShot
import dev.openflight.companion.core.insights.buildExportCsv
import dev.openflight.companion.core.insights.buildSessionsCsvFilename
import dev.openflight.companion.core.insights.withEstimatedTotals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The session history list (plan R8h, the Expo app's Shots tab session list): every stored
 * session, newest first, with its day, shot count, first/last shot time and how it was recorded.
 *
 * Plan R8f: "Clear all history" asks first, then shows pending until the repository says the clear
 * was applied, then done; storage that fails it, or doesn't answer within [HISTORY_TIMEOUT_MILLIS],
 * fails with a retry.
 *
 * Select mode picks several sessions to export as one CSV ([SessionHistoryEvent.ExportSelected]).
 */
@OptIn(ExperimentalTime::class)
class SessionHistoryViewModel(
    private val history: ShotHistoryRepository,
    // Plan F5b: the export's estimated roll and total use the current conditions.
    private val conditions: ConditionsRepository,
    private val now: () -> String = { Clock.System.now().toString() },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val action = MutableStateFlow<SessionActionState>(SessionActionState.Idle)
    private var actionJob: Job? = null
    private val selection = MutableStateFlow(Selection())

    val uiState: StateFlow<SessionHistoryUiState> =
        combine(history.sessions(), history.currentSessionId, history.isPersistent, action, selection) {
            sessions,
            current,
            persistent,
            action,
            picked,
            ->
            val year = now().take(YEAR_LENGTH).toIntOrNull()
            val listed = sessions.map { it.id }.toSet()
            SessionHistoryUiState(
                loaded = true,
                sessions = sessions.map { it.toRow(current, year) },
                isPersistent = persistent,
                action = action,
                selecting = picked.active,
                // A session cleared or deleted meanwhile drops out of the selection.
                selectedIds = picked.ids intersect listed,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionHistoryUiState(),
        )

    private val historyEffects = Channel<SessionEffect>(Channel.BUFFERED)

    /** A finished multi-session export ([SessionEffect.CsvReady]). */
    val effects: Flow<SessionEffect> = historyEffects.receiveAsFlow()

    fun onEvent(event: SessionHistoryEvent) {
        when (event) {
            is SessionHistorySelectionEvent -> {
                onSelectionEvent(event)
            }

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

    /** Select mode: picking sessions, and exporting the picked ones as one CSV. */
    private fun onSelectionEvent(event: SessionHistorySelectionEvent) {
        when (event) {
            SessionHistoryEvent.StartSelecting -> {
                selection.value = Selection(active = true)
            }

            SessionHistoryEvent.StopSelecting -> {
                selection.value = Selection()
            }

            is SessionHistoryEvent.ToggleSelected -> {
                selection.update {
                    it.copy(
                        ids =
                            if (event.sessionId in
                                it.ids
                            ) {
                                it.ids - event.sessionId
                            } else {
                                it.ids + event.sessionId
                            },
                    )
                }
            }

            SessionHistoryEvent.ToggleSelectAll -> {
                val state = uiState.value
                selection.update {
                    it.copy(
                        ids =
                            if (state.allSelected) {
                                emptySet()
                            } else {
                                state.sessions
                                    .map { row ->
                                        row.id
                                    }.toSet()
                            },
                    )
                }
            }

            SessionHistoryEvent.ExportSelected -> {
                exportSelected()
            }
        }
    }

    /**
     * One CSV of the picked sessions, oldest first (each session's shots oldest first, like the
     * single-session export), every row naming its session by its first shot's date and time.
     */
    private fun exportSelected() {
        val picked = uiState.value.selectedIds
        if (picked.isEmpty()) return
        viewModelScope.launch {
            val sessions =
                history
                    .sessions()
                    .first()
                    .filter { it.id in picked }
                    .sortedBy { it.firstShotAt }
            val rows =
                sessions.flatMap { session ->
                    val label = session.firstShotAt.take(SESSION_LABEL_LENGTH).replace('T', ' ')
                    history.shots(session.id).first().asReversed().map {
                        ExportShot.of(it.detail, it.eventId).copy(session = label)
                    }
                }
            if (rows.isEmpty()) return@launch
            val air = conditions.conditions.value
            val bearing = conditions.targetBearing.value
            val csv = withContext(computeDispatcher) { buildExportCsv(rows.withEstimatedTotals(air, bearing)) }
            historyEffects.send(SessionEffect.CsvReady(csv, buildSessionsCsvFilename(now())))
        }
    }

    /** Select mode and the sessions picked in it. */
    private data class Selection(
        val active: Boolean = false,
        val ids: Set<String> = emptySet(),
    )

    /** Done once the repository applied the clear. */
    private fun clearAll() {
        val target = SessionAction.ClearAllHistory
        action.value = SessionActionState.Pending(target, SessionActionCopy.pending(target))
        actionJob?.cancel()
        actionJob =
            viewModelScope.launch {
                val cleared = withTimeoutOrNull(HISTORY_TIMEOUT_MILLIS) { history.clearAll() }
                action.value =
                    if (cleared == true) {
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
        /** How long the history store may take to apply a local delete or clear. */
        const val HISTORY_TIMEOUT_MILLIS: Long = 10_000
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val YEAR_LENGTH = 4

        /** `"2026-09-25T10:03"` → the session label `"2026-09-25 10:03"`. */
        private const val SESSION_LABEL_LENGTH = 16
    }
}
