// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.insights.buildShotsCsv
import dev.openflight.companion.core.insights.buildShotsCsvFilename
import dev.openflight.companion.core.insights.computeClubChips
import dev.openflight.companion.core.insights.computeClubStats
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The session/stats screen's state holder (plan R5a), ported from the web UI's `StatsView.tsx`
 * (stats + tabs) and `ShotList.tsx` (delete/clear/export). Unlike the web UI, delete and clear
 * are local-only ([ShotRepository.deleteShot]/[ShotRepository.clearHistory]); there's no
 * server-side equivalent over BLE/Wi-Fi (plan R5a "out of scope").
 */
@OptIn(ExperimentalTime::class)
class SessionViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val now: () -> String = { Clock.System.now().toString() },
) : ViewModel() {
    private val selectedClub = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SessionUiState> =
        combine(shots.history, settings.units, selectedClub) { history, units, selected ->
            val filtered = if (selected == null) history else history.filter { it.club == selected }
            SessionUiState(
                units = units,
                allCount = history.size,
                clubChips = computeClubChips(history),
                selectedClub = selected,
                stats = computeClubStats(filtered),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionUiState(),
        )

    private val sessionEffects = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = sessionEffects.receiveAsFlow()

    fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.SelectClub -> selectedClub.value = event.club
            is SessionEvent.DeleteShot -> shots.deleteShot(event.eventId)
            SessionEvent.ClearHistory -> shots.clearHistory()
            SessionEvent.ExportCsv -> exportCsv()
        }
    }

    /**
     * Exports the *full* history (not just the selected tab), matching the web UI's export: the
     * club tabs filter [SessionUiState.stats] but not `ShotList.tsx`'s export, which is a
     * separate component with its own (unfiltered) `shots` prop. History is oldest-first before
     * export, so `shot_number` 1 is the oldest shot, like the web UI's underlying shot array
     * ([dev.openflight.companion.core.insights.buildShotsCsv]'s numbering).
     */
    private fun exportCsv() {
        viewModelScope.launch {
            val oldestFirst: List<ShotEvent> = shots.history.value.asReversed()
            val csv = buildShotsCsv(oldestFirst)
            val filename = buildShotsCsvFilename(now())
            sessionEffects.send(SessionEffect.CsvReady(csv, filename))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
