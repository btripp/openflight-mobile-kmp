// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.HistorySession
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.insights.ExportShot
import dev.openflight.companion.core.insights.buildExportCsv
import dev.openflight.companion.core.insights.buildShotsCsvFilename
import dev.openflight.companion.core.insights.computeDetailClubChips
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.insights.computeSwingSpeedStats
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One stored session (plan R8h): the live Session screen's club tabs, stats and rows, computed with
 * the same `core:insights` functions over the stored shots, plus a per-session CSV export.
 *
 * Plan R8f: a profile filter (when the session holds more than one profile's shots), the day and
 * how it was recorded in the heading, and a confirmed delete with pending, done and failed states.
 * Rows keep their session-wide `#n` whichever profile or club is shown.
 */
@OptIn(ExperimentalTime::class)
class SessionHistoryDetailViewModel(
    private val sessionId: String,
    private val history: ShotHistoryRepository,
    settings: SettingsRepository,
    private val now: () -> String = { Clock.System.now().toString() },
) : ViewModel() {
    private val filter = MutableStateFlow(Filter(profileId = null, club = null))
    private val action = MutableStateFlow<SessionActionState>(SessionActionState.Idle)
    private var actionJob: Job? = null
    private val shots = history.shots(sessionId)
    private val session = history.sessions().map { sessions -> sessions.firstOrNull { it.id == sessionId } }
    private val heading = combine(session, history.currentSessionId, ::Heading)

    val uiState: StateFlow<SessionHistoryDetailUiState> =
        combine(shots, settings.units, filter, heading, action) { stored, units, picked, head, action ->
            detailState(stored, picked, head).let {
                it.copy(session = it.session.copy(units = units), action = action)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionHistoryDetailUiState(),
        )

    private val detailEffects = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = detailEffects.receiveAsFlow()

    fun onEvent(event: SessionHistoryDetailEvent) {
        when (event) {
            is SessionHistoryDetailEvent.SelectClub -> {
                filter.value = filter.value.copy(club = event.club)
            }

            // A profile change starts from every club, like the live screen.
            is SessionHistoryDetailEvent.SelectProfile -> {
                filter.value =
                    Filter(profileId = event.profileId, club = null)
            }

            is SessionHistoryDetailEvent.DeleteShot -> {
                confirmDelete(event.id)
            }

            SessionHistoryDetailEvent.ExportCsv -> {
                exportCsv()
            }

            SessionHistoryDetailEvent.ConfirmAction -> {
                (action.value as? SessionActionState.Confirming)?.let { delete(it.action) }
            }

            SessionHistoryDetailEvent.CancelAction -> {
                if (action.value is SessionActionState.Confirming) action.value = SessionActionState.Idle
            }

            SessionHistoryDetailEvent.RetryAction -> {
                (action.value as? SessionActionState.Failed)?.takeIf { it.canRetry }?.let { delete(it.action) }
            }

            SessionHistoryDetailEvent.DismissAction -> {
                if (action.value is SessionActionState.Done || action.value is SessionActionState.Failed) {
                    action.value = SessionActionState.Idle
                }
            }
        }
    }

    private fun confirmDelete(id: String) {
        if (action.value.isBusy) return
        val row =
            uiState.value.session.shots
                .firstOrNull { it.id == id }
        val target =
            SessionAction.DeleteShot(
                id = id,
                shotNumber = row?.shotNumber ?: 0,
                clubName = row?.let { it.implementLabel ?: clubName(it.club) }.orEmpty(),
            )
        action.value = SessionActionCopy.confirmHistoryDelete(target)
    }

    /** Done once the stored shot is gone; the store writes in the background. */
    private fun delete(target: SessionAction) {
        if (target !is SessionAction.DeleteShot) return
        action.value = SessionActionState.Pending(target, SessionActionCopy.pending(target))
        history.deleteShot(target.id)
        actionJob?.cancel()
        actionJob =
            viewModelScope.launch {
                val gone =
                    withTimeoutOrNull(SessionHistoryViewModel.HISTORY_TIMEOUT_MILLIS) {
                        shots.first { stored -> stored.none { it.detail.timestamp == target.id } }
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

    /** Oldest first, with the web export's profile/mode/implement columns, like the live export. */
    private fun exportCsv() {
        viewModelScope.launch {
            val stored = shots.first()
            if (stored.isEmpty()) return@launch
            val rows = stored.asReversed().map { ExportShot.of(it.detail, it.eventId) }
            detailEffects.send(
                SessionEffect.CsvReady(buildExportCsv(rows), buildShotsCsvFilename(stored.last().detail.timestamp)),
            )
        }
    }

    private fun detailState(
        stored: List<HistoryShot>,
        picked: Filter,
        head: Heading,
    ): SessionHistoryDetailUiState {
        val details = stored.map { it.detail }
        val profileChips = profileChips(details)
        // A profile that has no shots left here falls back to every profile.
        val profileId = picked.profileId?.takeIf { id -> profileChips.any { it.id == id } }
        val profileRows =
            piRows(details).filterIndexed { index, _ ->
                profileId == null ||
                    details[index].profileId == profileId
            }
        val profileDetails = if (profileId == null) details else details.filter { it.profileId == profileId }
        val clubChips = computeDetailClubChips(profileDetails)
        val selected = picked.club?.takeIf { club -> clubChips.any { it.club == club } }
        val filtered = if (selected == null) profileDetails else profileDetails.filter { it.club == selected }
        val isSwingSession = filtered.isNotEmpty() && filtered.all { it.isSwingSpeed }
        val first = details.lastOrNull()?.timestamp
        val last = details.firstOrNull()?.timestamp
        return SessionHistoryDetailUiState(
            loaded = true,
            title = first?.let { historyDay(it, now().take(YEAR_LENGTH).toIntOrNull()) }.orEmpty(),
            subtitle =
                if (first != null && last != null) {
                    "${historyTimeRange(first, last)} · ${if (details.size == 1) "1 shot" else "${details.size} shots"}"
                } else {
                    ""
                },
            sourceLine =
                head.session
                    ?.let { listOfNotNull(transportLabel(it.transport), it.host?.takeIf(String::isNotBlank)) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(" · "),
            isCurrent = head.currentSessionId == sessionId,
            profileChips = profileChips,
            selectedProfileId = profileId,
            session =
                SessionUiState(
                    source = SessionSource.LOCAL,
                    allCount = profileDetails.size,
                    clubChips = clubChips,
                    selectedClub = selected,
                    stats = computeDetailStats(filtered),
                    swingStats =
                        if (isSwingSession) {
                            computeSwingSpeedStats(filtered.asReversed(), profileId = null, trainingImplement = null)
                        } else {
                            null
                        },
                    shots = if (selected == null) profileRows else profileRows.filter { it.club == selected },
                ),
        )
    }

    /** One chip per profile, in first-shot order, when more than one profile hit here. */
    private fun profileChips(details: List<ShotDetail>): List<HistoryProfileChip> {
        val chips =
            details
                .asReversed()
                .filter { !it.profileId.isNullOrBlank() }
                .groupBy { it.profileId.orEmpty() }
                .map { (id, shots) ->
                    HistoryProfileChip(
                        id = id,
                        name = shots.firstNotNullOfOrNull { it.profileName?.takeIf(String::isNotBlank) } ?: id,
                        count = shots.size,
                    )
                }
        return if (chips.size > 1) chips else emptyList()
    }

    private data class Filter(
        val profileId: String?,
        val club: String?,
    )

    private data class Heading(
        val session: HistorySession?,
        val currentSessionId: String?,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val YEAR_LENGTH = 4
    }
}

private fun clubName(wire: String): String = GolfClub.fromWireValue(wire)?.displayName ?: wire.ifEmpty { "Unknown" }
