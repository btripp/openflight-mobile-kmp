// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.Bag
import dev.openflight.companion.core.data.BagRepository
import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotWindow
import dev.openflight.companion.core.insights.GappingClub
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.analyzeGapping
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.TargetBearing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot results My Bag reacts to. */
sealed interface BagEffect {
    /** The conditions editor's values were stored: close it. */
    data object ConditionsSaved : BagEffect
}

/**
 * My Bag's state holder (plan F5): the active bag (seeded with the default 14 on first open), its
 * editing (add, remove, reorder, make/model/loft), switching bags, and the conditions card, which
 * edits [ConditionsRepository] in manual mode and adjusts every carry shown.
 *
 * Carries come from the phone's stored history across every session that counts in stats (never
 * imported ones unless opted in), each adjusted for the current conditions by
 * [ClubShotDistances].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BagViewModel(
    private val bagRepository: BagRepository,
    history: ShotHistoryRepository,
    private val conditionsRepository: ConditionsRepository,
    settings: SettingsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val distances = ClubShotDistances()
    private val error = MutableStateFlow<String?>(null)
    private val formError = MutableStateFlow<String?>(null)
    private val seeded = MutableStateFlow(false)
    private val effectChannel = Channel<BagEffect>(Channel.BUFFERED)

    val effects: Flow<BagEffect> = effectChannel.receiveAsFlow()

    private val activeBag = bagRepository.activeBag()

    private val environment =
        combine(
            conditionsRepository.conditions,
            conditionsRepository.targetBearing,
            conditionsRepository.mode,
            settings.units,
        ) { conditions, bearing, mode, units -> Environment(conditions, bearing, mode, units) }

    private val statsByClub: Flow<Map<GolfClub, ClubStatsEntry>> =
        combine(
            activeBag
                .map { bag -> bag?.clubs?.map { it.club }.orEmpty() }
                .distinctUntilChanged()
                .flatMapLatest { clubs -> history.shotsByClub(clubs, ShotWindow.All) },
            conditionsRepository.conditions,
            conditionsRepository.targetBearing,
        ) { shots, conditions, bearing ->
            shots.mapValues { (_, clubShots) -> clubStats(distances.of(clubShots, conditions, bearing)) }
        }.flowOn(computeDispatcher)
            .onStart { emit(emptyMap()) }

    val uiState: StateFlow<BagUiState> =
        combine(
            combine(activeBag, bagRepository.bags(), seeded) { bag, bags, seeded -> Triple(bag, bags, seeded) },
            statsByClub,
            environment,
            error,
            formError,
        ) { (bag, bags, seeded), stats, environment, error, formError ->
            state(bag, bags, loaded = seeded || bag != null, stats, environment, error, formError)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BagUiState())

    init {
        viewModelScope.launch {
            bagRepository.seedDefaultBagIfEmpty()
            seeded.value = true
        }
    }

    fun onEvent(event: BagEvent) {
        when (event) {
            is BagEvent.MoveClub -> moveClub(event.clubId, event.up)
            is BagEvent.Reorder -> withBag { bag -> bagRepository.reorder(bag.id, event.clubIds) }
            is BagEvent.RemoveClub -> viewModelScope.launch { bagRepository.removeClub(event.clubId) }
            is BagEvent.AddClub -> withBag { bag -> bagRepository.upsertClub(bag.id, event.club) ?: fail(SAVE_FAILED) }
            is BagEvent.EditClub -> editClub(event)
            is BagEvent.SelectBag -> viewModelScope.launch { bagRepository.setActive(event.bagId) }
            is BagEvent.SaveConditions -> saveConditions(event.form)
            BagEvent.DismissError -> error.value = null
        }
    }

    private fun moveClub(
        clubId: String,
        up: Boolean,
    ) = withBag { bag ->
        val ids = bag.clubs.map { it.id }
        val from = ids.indexOf(clubId)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in ids.indices) return@withBag
        val reordered = ids.toMutableList().apply { add(to, removeAt(from)) }
        bagRepository.reorder(bag.id, reordered)
    }

    private fun editClub(event: BagEvent.EditClub) {
        val loft =
            if (event.loft.isBlank()) {
                null
            } else {
                event.loft
                    .trim()
                    .toDoubleOrNull()
                    ?.takeIf { it in 0.0..MAX_LOFT_DEG } ?: run {
                    error.value = "Loft must be a number of degrees between 0 and $MAX_LOFT_DEG_LABEL."
                    return
                }
            }
        withBag { bag ->
            val club = bag.clubs.firstOrNull { it.id == event.clubId } ?: return@withBag
            bagRepository.upsertClub(
                bagId = bag.id,
                club = club.club,
                make = event.make.trim().ifEmpty { null },
                model = event.model.trim().ifEmpty { null },
                loftDeg = loft,
                note = club.note,
            ) ?: fail(SAVE_FAILED)
        }
    }

    private fun saveConditions(form: ConditionsForm) {
        viewModelScope.launch {
            val units = environment.first().units
            when (val result = form.parse(units)) {
                is ConditionsFormResult.Invalid -> {
                    formError.value = result.message
                }

                is ConditionsFormResult.Valid -> {
                    formError.value = null
                    conditionsRepository.setManual(result.conditions)
                    conditionsRepository.setTargetBearing(result.targetBearing)
                    effectChannel.send(BagEffect.ConditionsSaved)
                }
            }
        }
    }

    private fun withBag(action: suspend (Bag) -> Unit) {
        viewModelScope.launch {
            val bag = activeBag.first() ?: return@launch
            action(bag)
        }
    }

    private fun fail(message: String) {
        error.value = message
    }

    private data class Environment(
        val conditions: Conditions,
        val bearing: TargetBearing?,
        val mode: ConditionsMode,
        val units: UnitSystem,
    )

    @Suppress("LongParameterList") // One state from every source.
    private fun state(
        bag: Bag?,
        bags: List<Bag>,
        loaded: Boolean,
        stats: Map<GolfClub, ClubStatsEntry>,
        environment: Environment,
        error: String?,
        formError: String?,
    ): BagUiState {
        val units = environment.units
        val clubs = bag?.clubs.orEmpty()
        val gapping = analyzeGapping(clubs.map { GappingClub(it.club, stats[it.club]?.summary) })
        val gapsByLonger = gapping.gaps.associateBy { it.longer }
        val colors = colorIndexes(clubs.map { it.club })
        val rows =
            clubs.map { entry ->
                val summary = stats[entry.club]?.summary
                val gap = gapsByLonger[entry.club]
                BagClubRow(
                    id = entry.id,
                    club = entry.club,
                    wireValue = entry.club.wireValue,
                    name = entry.club.displayName,
                    shortLabel = entry.club.shortLabel,
                    makeModel = listOfNotNull(entry.make, entry.model).joinToString(" ").ifEmpty { null },
                    make = entry.make,
                    model = entry.model,
                    loftDeg = entry.loftDeg,
                    avgCarryYards = summary?.meanCarryYards,
                    carryLabel = BagCopy.distance(summary?.meanCarryYards, units),
                    plusMinusLabel =
                        summary?.takeIf { it.shotCount >= 2 }?.let {
                            BagCopy.plusMinus(
                                it.stdDevCarryYards,
                                units,
                            )
                        },
                    shotCountLabel = BagCopy.shotCount(summary?.shotCount ?: 0),
                    colorIndex = colors.getValue(entry.club),
                    gap = gap?.let { GapChipState(BagCopy.gapChip(it.gapYards, it.flag, units), it.flag) },
                )
            }
        return BagUiState(
            loaded = loaded,
            bagId = bag?.id,
            bagName = bag?.name.orEmpty(),
            bags = bags.map { BagOption(it.id, it.name, it.isActive) },
            clubs = rows,
            addableClubs = GolfClub.entries.filter { club -> clubs.none { it.club == club } },
            conditions = conditionsCard(environment, formError),
            units = units,
            carryAdjusted = stats.values.any { entry -> entry.shots.any { it.carryAdjusted } },
            error = error,
        )
    }

    private fun conditionsCard(
        environment: Environment,
        formError: String?,
    ): BagConditionsCardState {
        val conditions = environment.conditions
        val units = environment.units
        return BagConditionsCardState(
            mode = environment.mode,
            modeLabel = BagCopy.mode(environment.mode),
            summary = BagCopy.conditionsSummary(conditions, environment.bearing, units),
            isStandard = conditions.copy(surface = Conditions.ISA.surface) == Conditions.ISA,
            windNote = BagCopy.WIND_NEEDS_TARGET.takeIf { !conditions.wind.isCalm && environment.bearing == null },
            form = ConditionsForm.of(conditions, environment.bearing, units),
            altitudeUnit = ConditionsForm.altitudeUnit(units),
            temperatureUnit = ConditionsForm.temperatureUnit(units),
            windUnit = ConditionsForm.windUnit(units),
            formError = formError,
        )
    }

    companion object {
        const val SAVE_FAILED: String = "Couldn't save the bag. Try again."
        private const val MAX_LOFT_DEG = 70.0
        private const val MAX_LOFT_DEG_LABEL = 70
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
