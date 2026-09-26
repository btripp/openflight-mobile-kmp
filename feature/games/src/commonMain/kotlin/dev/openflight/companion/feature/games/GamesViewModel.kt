// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.ActiveGameRepository
import dev.openflight.companion.core.data.ActivityRepository
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.FinalShotStream
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.CalloutContext
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The games screen's state holder (plan F9): runs the live shots through [GameSessionReducer].
 *
 * - Each shot is attributed to whoever is up when its `event_id` is first sighted and scored when
 *   it turns final ([FinalShotStream], A14).
 * - While a game runs it publishes the call-out context (game, target, player) through
 *   [ActiveGameRepository], and clears it when the game ends or the screen goes away (A2).
 * - End files the game through [ActivityRepository], linked to the history session its shots went
 *   to ([ShotHistoryRepository.currentSessionId]).
 * - Games count only real shots: without a connected Pi the screen shows "Connect to play". A
 *   `--mock` Pi offers "Simulate shot" (`simulate_shot`, Wi-Fi only), like the Session screen.
 *
 * @param launch the typed navigation argument (A6): the mode and distance to open on.
 */
@Suppress("LongParameterList", "TooManyFunctions") // The repositories a game touches; one handler per intent.
class GamesViewModel(
    private val shots: ShotRepository,
    private val finalShots: FinalShotStream,
    private val piSession: PiSessionRepository,
    private val activeGame: ActiveGameRepository,
    private val activities: ActivityRepository,
    private val history: ShotHistoryRepository,
    private val conditions: ConditionsRepository,
    private val settings: SettingsRepository,
    launch: GameLaunch? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random.Default,
    private val newId: () -> String = ::randomId,
) : ViewModel() {
    private val measurer = GameShotMeasurer()
    private val setup = MutableStateFlow(GameSetup.from(launch))
    private val game = MutableStateFlow<GameState?>(null)
    private val saved = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val effectChannel = Channel<GamesEffect>(Channel.BUFFERED)
    private var publishedCallout = false

    val effects: Flow<GamesEffect> = effectChannel.receiveAsFlow()

    private val local = combine(setup, game, saved, error, ::Local)
    private val simulate =
        combine(piSession.mockMode, piSession.linkState) { mock, link ->
            if (mock == true) SimulateAction(PiFeatureAvailability.of(link)) else null
        }

    val uiState: StateFlow<GamesUiState> =
        combine(local, shots.connectionState, simulate, settings.units) { local, connection, simulate, units ->
            state(local, connection == ConnectionState.Connected, simulate, units)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = GamesUiState.ConnectToPlay(setup.value, SettingsRepository.DEFAULT_UNITS),
        )

    init {
        viewModelScope.launch {
            finalShots.firstSightings().collect { reduce(GameEvent.ShotSighted(it.eventId)) }
        }
        viewModelScope.launch {
            finalShots.finalShots().collect { shot ->
                if (game.value == null) return@collect
                val measured = measurer.measure(shot, conditions.conditions.value, conditions.targetBearing.value)
                reduce(GameEvent.ShotFinal(measured))
            }
        }
        viewModelScope.launch { game.collect(::publishCallout) }
    }

    fun onEvent(event: GamesEvent) {
        when (event) {
            GamesEvent.Start -> start()
            GamesEvent.Undo -> reduce(GameEvent.Undo)
            GamesEvent.Skip -> reduce(GameEvent.Skip)
            GamesEvent.Pause -> reduce(GameEvent.Pause)
            GamesEvent.Resume -> reduce(GameEvent.Resume)
            GamesEvent.End -> end()
            GamesEvent.PlayAgain -> playAgain()
            GamesEvent.SimulateShot -> simulateShot()
            GamesEvent.DismissError -> error.value = null
            else -> onSetupEvent(event)
        }
    }

    /** The setup sheet's edits; they apply only while no game is running. */
    @Suppress("CyclomaticComplexMethod") // One branch per setting.
    private fun onSetupEvent(event: GamesEvent) {
        when (event) {
            is GamesEvent.SelectType -> {
                editSetup { it.copy(type = event.type) }
            }

            is GamesEvent.SetTargetYards -> {
                editSetup { it.copy(targetYards = event.yards.clampTarget()) }
            }

            is GamesEvent.SetPinLateralYards -> {
                editSetup { it.copy(pinLateralYards = event.yards.coerceIn(-MAX_LATERAL_YARDS, MAX_LATERAL_YARDS)) }
            }

            is GamesEvent.SetPlanKind -> {
                editSetup { it.copy(planKind = event.kind) }
            }

            is GamesEvent.SetRandomRange -> {
                setRandomRange(event.minYards, event.maxYards)
            }

            is GamesEvent.SetMetric -> {
                editSetup { it.copy(metric = event.metric) }
            }

            is GamesEvent.SetShotsPerPlayer -> {
                editSetup { it.copy(shotsPerPlayer = event.shots.coerceIn(1, GameSetup.MAX_SHOTS_PER_PLAYER)) }
            }

            is GamesEvent.SetShotsPerTurn -> {
                editSetup { it.copy(shotsPerTurn = event.shots.coerceIn(1, GameSetup.MAX_SHOTS_PER_PLAYER)) }
            }

            // The plan's two sizes: 6 or 10 cups.
            is GamesEvent.SetPongCups -> {
                val large = event.cups >= GameMode.GolfPong.LARGE_CUPS
                val cups = if (large) GameMode.GolfPong.LARGE_CUPS else GameMode.GolfPong.DEFAULT_CUPS
                editSetup { it.copy(pongCups = cups) }
            }

            is GamesEvent.SelectIconicShot -> {
                if (IconicShotCatalog.find(event.id) != null) editSetup { it.copy(iconicShotId = event.id) }
            }

            GamesEvent.AddPlayer -> {
                addPlayer()
            }

            is GamesEvent.RenamePlayer -> {
                renamePlayer(event.playerId, event.name)
            }

            is GamesEvent.RemovePlayer -> {
                removePlayer(event.playerId)
            }

            else -> {
                Unit
            }
        }
    }

    override fun onCleared() {
        if (publishedCallout) activeGame.clear()
        publishedCallout = false
        super.onCleared()
    }

    private fun reduce(event: GameEvent) {
        game.update { state -> state?.let { GameSessionReducer.reduce(it, event) } }
    }

    private fun editSetup(change: (GameSetup) -> GameSetup) {
        if (game.value == null) setup.update(change)
    }

    private fun setRandomRange(
        minYards: Double,
        maxYards: Double,
    ) {
        val low = minOf(minYards, maxYards).clampTarget()
        val high = maxOf(minYards, maxYards).clampTarget()
        if (high - low < TargetPlan.DEFAULT_RANDOM_STEP) return
        editSetup { it.copy(randomMinYards = low, randomMaxYards = high) }
    }

    private fun addPlayer() =
        editSetup { current ->
            if (!current.canAddPlayer) return@editSetup current
            val usedIds = current.players.map { it.id }.toSet()
            val usedColors = current.players.map { it.colorIndex }.toSet()
            val index = generateSequence(0) { it + 1 }.first { GameSetup.defaultPlayer(it).id !in usedIds }
            val color = (0 until GameConfig.MAX_PLAYERS).firstOrNull { it !in usedColors } ?: index
            current.copy(players = current.players + GameSetup.defaultPlayer(index).copy(colorIndex = color))
        }

    private fun renamePlayer(
        playerId: String,
        name: String,
    ) {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        editSetup { current ->
            current.copy(players = current.players.map { if (it.id == playerId) it.copy(name = trimmed) else it })
        }
    }

    private fun removePlayer(playerId: String) =
        editSetup { current ->
            val players = current.players.filterNot { it.id == playerId }
            if (players.isEmpty()) current else current.copy(players = players)
        }

    private fun start() {
        if (game.value != null || shots.connectionState.value != ConnectionState.Connected) return
        saved.value = false
        game.value = GameSessionReducer.reduce(GameState.new(setup.value.toConfig(random)), GameEvent.Start(now()))
    }

    private fun end() {
        val current = game.value?.takeIf { it.status != GameStatus.ENDED && it.status != GameStatus.NOT_STARTED }
        if (current == null) return
        val ended = GameSessionReducer.reduce(current, GameEvent.End(now()))
        game.value = ended
        if (ended.attributions.none { it.score != null }) return
        val activity = GameRecords.activity(newId(), ended, uiState.value.units, history.currentSessionId.value)
        viewModelScope.launch {
            activities.record(activity)
            saved.value = true
            effectChannel.send(GamesEffect.Saved(activity.id))
        }
    }

    private fun playAgain() {
        if (game.value?.status != GameStatus.ENDED) return
        game.value = null
        saved.value = false
    }

    @Suppress("TooGenericExceptionCaught") // Every command failure is shown the same way.
    private fun simulateShot() {
        viewModelScope.launch {
            try {
                piSession.simulateShot()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                error.value = failure.message ?: SIMULATE_FAILED
            }
        }
    }

    /** Tells the call-outs about the running game, and forgets it once the game stops. */
    private fun publishCallout(state: GameState?) {
        val running = state != null && (state.status == GameStatus.IN_PROGRESS || state.status == GameStatus.PAUSED)
        val player = state?.currentPlayer
        if (running && player != null) {
            activeGame.set(
                CalloutContext(
                    gameTitle = state.mode.type.title,
                    targetYards = state.currentTarget?.distanceYards,
                    playerName = player.name.takeIf { state.players.size > 1 },
                ),
            )
            publishedCallout = true
        } else if (publishedCallout) {
            activeGame.clear()
            publishedCallout = false
        }
    }

    private fun state(
        local: Local,
        connected: Boolean,
        simulate: SimulateAction?,
        units: UnitSystem,
    ): GamesUiState {
        val game = local.game
        return when {
            game == null && !connected -> {
                GamesUiState.ConnectToPlay(local.setup, units, simulate)
            }

            game == null -> {
                GamesUiState.Setup(local.setup, units, simulate)
            }

            game.status == GameStatus.ENDED -> {
                GamesUiState.Results(game.toView(units), units, GameCopy.headline(game, units), local.saved)
            }

            else -> {
                GamesUiState.Playing(game.toView(units), units, connected, simulate, local.error)
            }
        }
    }

    private data class Local(
        val setup: GameSetup,
        val game: GameState?,
        val saved: Boolean,
        val error: String?,
    )

    companion object {
        const val SIMULATE_FAILED = "Couldn't simulate a shot."
        const val MAX_NAME_LENGTH = 20
        const val MAX_LATERAL_YARDS = 50.0
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private fun Double.clampTarget() = coerceIn(GameSetup.MIN_TARGET_YARDS, GameSetup.MAX_TARGET_YARDS)

        @OptIn(ExperimentalUuidApi::class)
        private fun randomId(): String = Uuid.random().toString()
    }
}

/** The view of this game in [units]. */
internal fun GameState.toView(units: UnitSystem): GameView {
    val player = currentPlayer
    val target = currentTarget
    val winners = if (isComplete || status == GameStatus.ENDED) this.winners else emptyList()
    val quota = mode.shotsPerPlayer
    val last = attributions.lastOrNull { it.score != null }
    val lastPlayer = last?.let { attribution -> players.firstOrNull { it.id == attribution.playerId } }
    return GameView(
        title = GameCopy.title(mode, units),
        type = mode.type,
        status = status,
        currentPlayer = player,
        turnLabel = player?.let(GameCopy::turnLabel),
        targetYards = target?.distanceYards,
        targetLabel = target?.let { GameCopy.distance(it.distanceYards, units) },
        lastShot =
            if (last?.score != null && lastPlayer != null) {
                LastShotView(
                    player = lastPlayer,
                    label = GameCopy.scoreLabel(mode, last.score, units),
                    deltaYards = last.score.deltaYards,
                    estimated = last.score.metricEstimated,
                    lateralUnknown = last.score.lateralUnknown,
                )
            } else {
                null
            },
        scoreboard =
            standings.map { standing ->
                ScoreboardRow(
                    player = standing.player,
                    shotsLabel = if (quota != null) "${standing.shotsTaken} / $quota" else "${standing.shotsTaken}",
                    totalLabel = GameCopy.totalLabel(mode, standing.total, units),
                    isCurrent = standing.player.id == player?.id,
                    isWinner = standing.player in winners,
                )
            },
        dots =
            attributions.mapNotNull { attribution ->
                val shot = attribution.shot ?: return@mapNotNull null
                TargetDot(
                    eventId = attribution.eventId,
                    colorIndex = players.firstOrNull { it.id == attribution.playerId }?.colorIndex ?: 0,
                    longYards = shot.carryYards - attribution.target.distanceYards,
                    offlineYards = shot.offlineYards?.let { it - attribution.target.lateralYards },
                )
            },
        winners = winners,
        canUndo = attributions.isNotEmpty() && status != GameStatus.ENDED,
    )
}
