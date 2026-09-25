// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.insights.ExportShot
import dev.openflight.companion.core.insights.buildExportCsv
import dev.openflight.companion.core.insights.buildShotsCsvFilename
import dev.openflight.companion.core.insights.computeDetailClubChips
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.insights.computeSwingSpeedStats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One stored session (plan R8h): the live Session screen's club tabs, stats and rows, computed with
 * the same `core:insights` functions over the stored shots, plus a per-session CSV export.
 */
class SessionHistoryDetailViewModel(
    private val sessionId: String,
    private val history: ShotHistoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val selectedClub = MutableStateFlow<String?>(null)
    private val shots = history.shots(sessionId)

    val uiState: StateFlow<SessionHistoryDetailUiState> =
        combine(shots, settings.units, selectedClub) { stored, units, selected ->
            detailState(stored, selected).let { it.copy(session = it.session.copy(units = units)) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionHistoryDetailUiState(),
        )

    private val detailEffects = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = detailEffects.receiveAsFlow()

    fun onEvent(event: SessionHistoryDetailEvent) {
        when (event) {
            is SessionHistoryDetailEvent.SelectClub -> selectedClub.value = event.club
            is SessionHistoryDetailEvent.DeleteShot -> history.deleteShot(event.id)
            SessionHistoryDetailEvent.ExportCsv -> exportCsv()
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
        selected: String?,
    ): SessionHistoryDetailUiState {
        val details = stored.map { it.detail }
        val filtered = if (selected == null) details else details.filter { it.club == selected }
        val isSwingSession = filtered.isNotEmpty() && filtered.all { it.isSwingSpeed }
        val first = details.lastOrNull()?.timestamp
        val last = details.firstOrNull()?.timestamp
        return SessionHistoryDetailUiState(
            loaded = true,
            title = first?.let(::historyDate).orEmpty(),
            subtitle =
                if (first != null && last != null) {
                    "${historyTimeRange(first, last)} · ${if (details.size == 1) "1 shot" else "${details.size} shots"}"
                } else {
                    ""
                },
            session =
                SessionUiState(
                    source = SessionSource.LOCAL,
                    allCount = details.size,
                    clubChips = computeDetailClubChips(details),
                    selectedClub = selected,
                    stats = computeDetailStats(filtered),
                    swingStats =
                        if (isSwingSession) {
                            computeSwingSpeedStats(filtered.asReversed(), profileId = null, trainingImplement = null)
                        } else {
                            null
                        },
                    shots = piRows(details),
                ),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
