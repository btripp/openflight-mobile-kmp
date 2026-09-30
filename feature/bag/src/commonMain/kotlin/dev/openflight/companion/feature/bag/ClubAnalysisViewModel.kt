// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.BagRepository
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.insights.GapInsight
import dev.openflight.companion.core.insights.GappingClub
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.analyzeGapping
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Which shots Club Analysis counts. */
enum class AnalysisWindow(
    val label: String,
    internal val shotWindow: ShotWindow,
) {
    LIFETIME("Lifetime", ShotWindow.All),
    LAST_5_SESSIONS("Last 5 sessions", ShotWindow.LastSessions(LAST_SESSIONS_SHORT)),
    LAST_10_SESSIONS("Last 10 sessions", ShotWindow.LastSessions(LAST_SESSIONS_LONG)),
}

private const val LAST_SESSIONS_SHORT = 5
private const val LAST_SESSIONS_LONG = 10

/** What the bars measure. Total is always an estimate (carry + estimated roll). */
enum class AnalysisMetric(
    val label: String,
    val isEstimated: Boolean,
) {
    CARRY("Carry", isEstimated = false),
    TOTAL("Total (est.)", isEstimated = true),
}

/**
 * Club Analysis (plan F5): the bag's clubs with shots, longest first, as bars scaled to the
 * longest, plus the gapping insights.
 *
 * @property adjustedForConditions the numbers use the conditions card's air, not the server's ISA.
 */
data class ClubAnalysisUiState(
    val loaded: Boolean = false,
    val window: AnalysisWindow = AnalysisWindow.LIFETIME,
    val metric: AnalysisMetric = AnalysisMetric.CARRY,
    val windows: List<AnalysisWindow> = AnalysisWindow.entries,
    val metrics: List<AnalysisMetric> = AnalysisMetric.entries,
    val bars: List<ClubBarRow> = emptyList(),
    val insights: List<GapInsight> = emptyList(),
    val insightTexts: List<String> = emptyList(),
    val adjustedForConditions: Boolean = false,
    val units: UnitSystem = UnitSystem.IMPERIAL,
)

/**
 * One bar.
 *
 * @property fraction the bar's length, 0–1 of the longest club's value.
 * @property estimated the value is an estimate (Total), so it's badged "est.".
 */
data class ClubBarRow(
    val club: GolfClub,
    val wireValue: String,
    val name: String,
    val shortLabel: String,
    val valueYards: Double,
    val valueLabel: String,
    val plusMinusLabel: String?,
    val shotCountLabel: String,
    val fraction: Double,
    val estimated: Boolean,
    val colorIndex: Int,
) {
    val accessibilityLabel: String
        get() =
            listOfNotNull(
                name,
                valueLabel + if (estimated) " estimated" else "",
                plusMinusLabel,
                shotCountLabel,
            ).joinToString(", ")
}

sealed interface ClubAnalysisEvent {
    data class SelectWindow(
        val window: AnalysisWindow,
    ) : ClubAnalysisEvent

    data class SelectMetric(
        val metric: AnalysisMetric,
    ) : ClubAnalysisEvent
}

/** Club Analysis' state holder (plan F5). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClubAnalysisViewModel(
    bags: BagRepository,
    history: ShotHistoryRepository,
    conditions: ConditionsRepository,
    settings: SettingsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val distances = ClubShotDistances()
    private val window = MutableStateFlow(AnalysisWindow.LIFETIME)
    private val metric = MutableStateFlow(AnalysisMetric.CARRY)

    private val bagClubs = bags.activeBag().map { bag -> bag?.clubs?.map { it.club }.orEmpty() }.distinctUntilChanged()

    private val stats =
        combine(
            combine(bagClubs, window) { clubs, window -> clubs to window }
                .flatMapLatest { (clubs, window) ->
                    history.shotsByClub(clubs, window.shotWindow).map { shots -> Triple(clubs, window, shots) }
                },
            conditions.conditions,
            conditions.targetBearing,
        ) { (clubs, window, shots), conditionsValue, bearing ->
            Stats(
                clubs,
                window,
                shots.mapValues { (_, list) ->
                    clubStats(distances.of(list, conditionsValue, bearing))
                },
            )
        }.flowOn(computeDispatcher)

    val uiState: StateFlow<ClubAnalysisUiState> =
        combine(stats, metric, settings.units) { stats, metric, units -> state(stats, metric, units) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ClubAnalysisUiState())

    fun onEvent(event: ClubAnalysisEvent) {
        when (event) {
            is ClubAnalysisEvent.SelectWindow -> window.value = event.window
            is ClubAnalysisEvent.SelectMetric -> metric.value = event.metric
        }
    }

    private data class Stats(
        val clubs: List<GolfClub>,
        val window: AnalysisWindow,
        val byClub: Map<GolfClub, ClubStatsEntry>,
    )

    private fun state(
        stats: Stats,
        metric: AnalysisMetric,
        units: UnitSystem,
    ): ClubAnalysisUiState {
        val colors = colorIndexes(stats.clubs)
        val values =
            stats.clubs.mapNotNull { club ->
                val summary = stats.byClub[club]?.summary ?: return@mapNotNull null
                val value =
                    when (metric) {
                        AnalysisMetric.CARRY -> summary.meanCarryYards
                        AnalysisMetric.TOTAL -> summary.meanTotalYards
                    } ?: return@mapNotNull null
                Triple(club, summary, value)
            }
        val longest = values.maxOfOrNull { it.third }?.takeIf { it > 0 } ?: 1.0
        val bars =
            values.sortedByDescending { it.third }.map { (club, summary, value) ->
                ClubBarRow(
                    club = club,
                    wireValue = club.wireValue,
                    name = club.displayName,
                    shortLabel = club.shortLabel,
                    valueYards = value,
                    valueLabel = BagCopy.distance(value, units),
                    plusMinusLabel =
                        summary.takeIf { metric == AnalysisMetric.CARRY && it.shotCount >= 2 }?.let {
                            BagCopy.plusMinus(it.stdDevCarryYards, units)
                        },
                    shotCountLabel = BagCopy.shotCount(summary.shotCount),
                    fraction = (value / longest).coerceIn(0.0, 1.0),
                    estimated = metric.isEstimated,
                    colorIndex = colors.getValue(club),
                )
            }
        // A club never hit isn't worth a line each (a new bag would list all 14).
        val insights =
            analyzeGapping(stats.clubs.map { GappingClub(it, stats.byClub[it]?.summary) })
                .insights
                .filter { it !is GapInsight.InsufficientData || it.shotCount > 0 }
        return ClubAnalysisUiState(
            loaded = true,
            window = stats.window,
            metric = metric,
            bars = bars,
            insights = insights,
            insightTexts = insights.map { BagCopy.insight(it, units) },
            adjustedForConditions = stats.byClub.values.any { entry -> entry.shots.any { it.carryAdjusted } },
            units = units,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
