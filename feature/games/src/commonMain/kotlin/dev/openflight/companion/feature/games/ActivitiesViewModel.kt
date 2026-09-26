// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.Activity
import dev.openflight.companion.core.data.ActivityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Activities hub (plan F9): finished games as a grid of cards, newest first, filtered by
 * type, with one card's detail.
 */
class ActivitiesViewModel(
    private val activities: ActivityRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<GameType?>(null)
    private val selectedId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val shown = filter.flatMapLatest { type -> activities.activities(type?.storageValue) }

    val uiState: StateFlow<ActivitiesUiState> =
        combine(shown, filter, selectedId) { list, type, selected ->
            if (list.isEmpty()) {
                ActivitiesUiState.Empty(filter = type)
            } else {
                ActivitiesUiState.Content(
                    cards = list.map { it.toCard() },
                    filter = type,
                    detail = list.firstOrNull { it.id == selected }?.toDetail(),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ActivitiesUiState.Loading,
        )

    fun onEvent(event: ActivitiesEvent) {
        when (event) {
            is ActivitiesEvent.SelectFilter -> {
                filter.value = event.type
            }

            is ActivitiesEvent.Select -> {
                selectedId.value = event.id
            }

            ActivitiesEvent.ClearSelection -> {
                selectedId.value = null
            }

            is ActivitiesEvent.Delete -> {
                if (selectedId.value == event.id) selectedId.value = null
                viewModelScope.launch { activities.delete(event.id) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The Activities hub. */
sealed interface ActivitiesUiState {
    /** The type filter chips: "All" (`null`) then every [GameType]. */
    val filters: List<GameType?> get() = listOf(null) + GameType.entries

    data object Loading : ActivitiesUiState

    /** Nothing played yet (or nothing of [filter]'s type). */
    data class Empty(
        val filter: GameType?,
    ) : ActivitiesUiState

    /**
     * @property cards newest first.
     * @property detail the selected card's detail, or `null` with nothing selected.
     */
    data class Content(
        val cards: List<ActivityCard>,
        val filter: GameType?,
        val detail: ActivityDetail? = null,
    ) : ActivitiesUiState
}

/**
 * One card: type, big headline and date badge (the UI formats [startedAtEpochMillis] in the
 * device's locale).
 *
 * @property type `null` for an activity type this version doesn't know.
 */
data class ActivityCard(
    val id: String,
    val type: GameType?,
    val typeTitle: String,
    val title: String,
    val headline: String,
    val startedAtEpochMillis: Long,
)

/**
 * A card opened: who played, how each did, and every scored shot.
 *
 * @property sessionId the history session its shots are filed under, while it exists.
 */
data class ActivityDetail(
    val card: ActivityCard,
    val standings: List<ActivityStanding>,
    val shots: List<ShotRecord>,
    val sessionId: String?,
)

data class ActivityStanding(
    val playerId: String,
    val name: String,
    val colorIndex: Int,
    val shotsTaken: Int,
    val totalLabel: String,
    val isWinner: Boolean,
)

sealed interface ActivitiesEvent {
    /** Shows only [type] (`null` = all). */
    data class SelectFilter(
        val type: GameType?,
    ) : ActivitiesEvent

    data class Select(
        val id: String,
    ) : ActivitiesEvent

    data object ClearSelection : ActivitiesEvent

    data class Delete(
        val id: String,
    ) : ActivitiesEvent
}

internal fun Activity.toCard(): ActivityCard {
    val gameType = GameType.fromStorageValue(type)
    return ActivityCard(
        id = id,
        type = gameType,
        typeTitle = gameType?.title ?: type,
        title = title,
        headline = headline,
        startedAtEpochMillis = startedAtEpochMillis,
    )
}

internal fun Activity.toDetail(): ActivityDetail {
    val players = GameRecords.players(this)
    val result = GameRecords.result(this)
    val standings =
        result?.standings.orEmpty().map { standing ->
            val player = players.firstOrNull { it.id == standing.playerId }
            ActivityStanding(
                playerId = standing.playerId,
                name = player?.name ?: standing.playerId,
                colorIndex = player?.colorIndex ?: 0,
                shotsTaken = standing.shotsTaken,
                totalLabel = standing.totalLabel,
                isWinner = standing.playerId in result?.winnerIds.orEmpty(),
            )
        }
    return ActivityDetail(
        card = toCard(),
        standings = standings,
        shots = result?.shots.orEmpty(),
        sessionId = sessionId,
    )
}
