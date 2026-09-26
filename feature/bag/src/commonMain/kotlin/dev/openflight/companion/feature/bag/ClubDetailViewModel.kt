// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.insights.ClubDistanceSummary
import dev.openflight.companion.core.insights.DispersionEllipse
import dev.openflight.companion.core.insights.DispersionSample
import dev.openflight.companion.core.insights.DispersionViewport
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDispersionEllipse
import dev.openflight.companion.core.insights.computeDispersionViewport
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One club in depth (plan F5): its carry distribution, where its shots land, and its recent shots.
 * Possible bad reads (median/MAD, plan A13) are listed but left out of the stats and the ellipse.
 *
 * @property summaryLines "155 yds ± 5 carry", "Median 154 yds · p10–p90 148–161 yds", …
 * @property totalLabel the mean estimated total, badged "est." by the UI; `null` without one.
 * @property dispersion only shots with a measured side; `null` when there are none.
 */
data class ClubDetailUiState(
    val loaded: Boolean = false,
    val wireValue: String = "",
    val clubName: String = "",
    val shotCount: Int = 0,
    val summaryLines: List<String> = emptyList(),
    val totalLabel: String? = null,
    val excludedLabel: String? = null,
    val histogram: List<HistogramBin> = emptyList(),
    val dispersion: ClubDispersionState? = null,
    val recentShots: List<RecentShotRow> = emptyList(),
    val adjustedForConditions: Boolean = false,
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val window: AnalysisWindow = AnalysisWindow.LIFETIME,
    val windows: List<AnalysisWindow> = AnalysisWindow.entries,
)

/** What Club Detail's user can do. */
sealed interface ClubDetailEvent {
    /** Lifetime or the last N sessions, as on Club Analysis. */
    data class SelectWindow(
        val window: AnalysisWindow,
    ) : ClubDetailEvent
}

/** One histogram bar: carries in `[lowYards, highYards)`; [fraction] of the tallest bar. */
data class HistogramBin(
    val lowYards: Double,
    val highYards: Double,
    val count: Int,
    val fraction: Double,
    val label: String,
)

/** The landing spots and their 68% ellipse, framed by the shared [DispersionViewport]. */
data class ClubDispersionState(
    val samples: List<DispersionSample>,
    val ellipse: DispersionEllipse?,
    val viewport: DispersionViewport,
    val accessibilitySummary: String,
)

/** One recent shot. [totalLabel] is an estimate, badged "est.". */
data class RecentShotRow(
    val id: Long,
    val timeLabel: String,
    val carryLabel: String,
    val totalLabel: String?,
    val sideLabel: String?,
    val possibleBadRead: Boolean,
)

/** Club Detail's state holder for [wireValue] (a [GolfClub] wire value). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClubDetailViewModel(
    private val wireValue: String,
    history: ShotHistoryRepository,
    conditions: ConditionsRepository,
    settings: SettingsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val distances = ClubShotDistances()
    private val club = GolfClub.fromWireValue(wireValue)
    private val window = MutableStateFlow(AnalysisWindow.LIFETIME)

    private val stats =
        combine(
            window.flatMapLatest { selected ->
                val shots = club?.let { history.shotsForClub(it, selected.shotWindow) } ?: flowOf(emptyList())
                shots.map { selected to it }
            },
            conditions.conditions,
            conditions.targetBearing,
        ) { (selected, shots), conditionsValue, bearing ->
            selected to clubStats(distances.of(shots, conditionsValue, bearing))
        }.flowOn(computeDispatcher)

    val uiState: StateFlow<ClubDetailUiState> =
        combine(stats, settings.units) { (selected, stats), units -> state(stats, units).copy(window = selected) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                ClubDetailUiState(wireValue = wireValue, clubName = GolfClub.displayNameFor(wireValue)),
            )

    fun onEvent(event: ClubDetailEvent) {
        when (event) {
            is ClubDetailEvent.SelectWindow -> window.value = event.window
        }
    }

    private fun state(
        stats: ClubStatsEntry,
        units: UnitSystem,
    ): ClubDetailUiState {
        val summary = stats.summary
        val outliers = summary?.outlierIndexes.orEmpty()
        val kept = stats.shots.filterIndexed { index, _ -> index !in outliers }
        return ClubDetailUiState(
            loaded = true,
            wireValue = wireValue,
            clubName = GolfClub.displayNameFor(wireValue),
            shotCount = summary?.shotCount ?: 0,
            summaryLines = summary?.let { summaryLines(it, units) }.orEmpty(),
            totalLabel = summary?.meanTotalYards?.let { "${BagCopy.distance(it, units)} total" },
            excludedLabel =
                summary?.excludedCount?.takeIf { it > 0 }?.let {
                    if (it == 1) "1 possible bad read left out" else "$it possible bad reads left out"
                },
            histogram = carryHistogram(kept.map { it.carryYards }, units),
            dispersion = dispersionState(kept, summary, units),
            recentShots =
                stats.shots.withIndex().take(RECENT_SHOTS).map { (index, shot) ->
                    RecentShotRow(
                        id = shot.shot.id,
                        timeLabel = BagCopy.shotTime(shot.shot.detail.timestamp),
                        carryLabel = BagCopy.distance(shot.carryYards, units),
                        totalLabel = shot.totalYards?.let { BagCopy.distance(it, units) },
                        sideLabel = shot.offlineYards?.let { BagCopy.side(it, units) },
                        possibleBadRead = index in outliers,
                    )
                },
            adjustedForConditions = stats.shots.any { it.carryAdjusted },
            units = units,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val RECENT_SHOTS = 10
    }
}

/**
 * [carries] (yards) in bins of [HISTOGRAM_BIN_UNITS] display units (5 yds or 5 m), from the bin
 * holding the shortest to the one holding the longest, empty bins included so gaps show. Empty
 * without carries.
 */
fun carryHistogram(
    carries: List<Double>,
    units: UnitSystem,
): List<HistogramBin> {
    if (carries.isEmpty()) return emptyList()
    val unitsPerYard = convertDistanceFromYards(1.0, units)
    val converted = carries.map { it * unitsPerYard }
    val first = floor(converted.min() / HISTOGRAM_BIN_UNITS).toInt()
    val last = maxOf(first, ceil((converted.max() + BIN_EDGE) / HISTOGRAM_BIN_UNITS).toInt() - 1)
    val counts = IntArray(last - first + 1)
    converted.forEach { counts[floor(it / HISTOGRAM_BIN_UNITS).toInt() - first] += 1 }
    val tallest = counts.max().coerceAtLeast(1)
    return counts.mapIndexed { index, count ->
        val low = (first + index) * HISTOGRAM_BIN_UNITS
        HistogramBin(
            lowYards = low / unitsPerYard,
            highYards = (low + HISTOGRAM_BIN_UNITS) / unitsPerYard,
            count = count,
            fraction = count.toDouble() / tallest,
            label = "${low.toInt()}–${(low + HISTOGRAM_BIN_UNITS).toInt()}",
        )
    }
}

/** Bin width in display units. */
const val HISTOGRAM_BIN_UNITS: Double = 5.0

/** Nudges an exact bin edge into the bin above, so 160 falls in 160–165, not past the end. */
private const val BIN_EDGE = 1e-9

/** "160 yds ± 2 yds carry", the median and middle 80%, the side bias and the shot count. */
private fun summaryLines(
    summary: ClubDistanceSummary,
    units: UnitSystem,
): List<String> {
    val carry = BagCopy.distance(summary.meanCarryYards, units)
    val spread = BagCopy.plusMinus(summary.plusMinusYards.toDouble(), units)
    val middle = BagCopy.distance(summary.p10CarryYards, units) + "–" + BagCopy.distance(summary.p90CarryYards, units)
    val side =
        summary.meanOfflineYards?.let { offline ->
            val sideSpread =
                summary.stdDevOfflineYards
                    ?.let {
                        ", ${BagCopy.plusMinus(
                            it,
                            units,
                        )} side to side"
                    }.orEmpty()
            "Typically ${BagCopy.side(offline, units)}$sideSpread"
        } ?: "Side not measured"
    return listOf(
        "$carry $spread carry",
        "Median ${BagCopy.distance(summary.medianCarryYards, units)} · middle 80% $middle",
        side,
        BagCopy.shotCount(summary.shotCount),
    )
}

/** The kept shots with a measured side, their ellipse and viewport; `null` when none has a side. */
private fun dispersionState(
    kept: List<ClubShot>,
    summary: ClubDistanceSummary?,
    units: UnitSystem,
): ClubDispersionState? {
    val measured = kept.mapNotNull { shot -> shot.offlineYards?.let { DispersionSample(it, shot.carryYards) } }
    val ellipse = computeDispersionEllipse(measured)
    val viewport =
        computeDispersionViewport(measured, listOfNotNull(ellipse), convertDistanceFromYards(1.0, units)) ?: return null
    val average = summary?.meanOfflineYards?.let { ", ${BagCopy.side(it, units)} on average" }.orEmpty()
    return ClubDispersionState(
        samples = measured,
        ellipse = ellipse,
        viewport = viewport,
        accessibilitySummary = "Dispersion of ${BagCopy.shotCount(measured.size)} with a measured side$average",
    )
}
