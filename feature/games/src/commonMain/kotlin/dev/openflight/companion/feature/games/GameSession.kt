// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

/**
 * A local player sharing the bay (plan F9, D3): a name and a colour slot, nothing on the Pi.
 *
 * @property colorIndex the player's colour, as a slot in the platform's player palette.
 */
data class Player(
    val id: String,
    val name: String,
    val colorIndex: Int,
)

/**
 * Round-robin turns: each player takes [shotsPerTurn] shots, then the next player whose shots
 * aren't used up goes.
 */
data class TurnOrder(
    val shotsPerTurn: Int = 1,
) {
    init {
        require(shotsPerTurn >= 1) { "A turn is at least one shot." }
    }
}

/** A game before it starts: its rules, its players (1–[MAX_PLAYERS]) and their turns. */
data class GameConfig(
    val mode: GameMode,
    val players: List<Player>,
    val turnOrder: TurnOrder = TurnOrder(),
) {
    init {
        require(players.size in 1..MAX_PLAYERS) { "A game has 1 to $MAX_PLAYERS players." }
        require(players.map { it.id }.distinct().size == players.size) { "Player ids must be unique." }
    }

    companion object {
        const val MAX_PLAYERS = 4
    }
}

enum class GameStatus {
    NOT_STARTED,
    IN_PROGRESS,

    /** New swings are ignored until resumed; a shot already sighted still scores when final. */
    PAUSED,

    /** Every shot is in, or someone won outright. Waiting for [GameEvent.End]. */
    COMPLETE,
    ENDED,
}

/** Whose turn it is: [playerIndex] into [GameConfig.players], with [shotsThisTurn] taken. */
data class TurnPosition(
    val playerIndex: Int,
    val shotsThisTurn: Int = 0,
)

/**
 * One swing, owned by the player whose turn it was when it was first sighted (A14), and scored
 * once its final version arrives.
 *
 * @property playerShotIndex this player's shot number, from 0.
 * @property target what it aimed at, fixed at the sighting.
 * @property turnBefore the turn as it was before this swing, restored by [GameEvent.Undo].
 */
data class Attribution(
    val eventId: String,
    val playerId: String,
    val playerShotIndex: Int,
    val target: GameTarget,
    val turnBefore: TurnPosition,
    val shot: GameShot? = null,
    val score: ShotScore? = null,
)

/**
 * Everything about a game in progress. Built only by [GameSessionReducer]: timestamps come in on
 * the events, so replaying the same events gives the same state.
 *
 * @property turn whose turn it is, or `null` when nobody has shots left (or it isn't running).
 * @property attributions every swing, in sighting order.
 * @property ignoredEventIds swings undone ([GameEvent.Undo]); their final versions are ignored.
 */
data class GameState(
    val config: GameConfig,
    val status: GameStatus = GameStatus.NOT_STARTED,
    val turn: TurnPosition? = null,
    val attributions: List<Attribution> = emptyList(),
    val ignoredEventIds: Set<String> = emptySet(),
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
) {
    val mode: GameMode get() = config.mode
    val players: List<Player> get() = config.players

    val currentPlayer: Player? get() = turn?.let { players.getOrNull(it.playerIndex) }

    /** Whether new swings count now. */
    val isAccepting: Boolean get() = status == GameStatus.IN_PROGRESS && turn != null

    /** [player]'s swings, oldest first. */
    fun attributionsOf(playerId: String): List<Attribution> = attributions.filter { it.playerId == playerId }

    /** [player]'s scored shots, in the order they scored. */
    fun scoresOf(playerId: String): List<ShotScore> = attributionsOf(playerId).mapNotNull { it.score }

    /** The target the current player's next swing aims at, or `null` when nobody is up. */
    val currentTarget: GameTarget?
        get() {
            val player = currentPlayer ?: return null
            return mode.target(attributionsOf(player.id).size, scoresOf(player.id))
        }

    /** Every player's standing, in player order. */
    val standings: List<PlayerStanding>
        get() =
            players.map { player ->
                val scores = scoresOf(player.id)
                PlayerStanding(
                    player = player,
                    shotsTaken = attributionsOf(player.id).size,
                    scores = scores,
                    total = mode.total(scores),
                    hasWon = mode.hasWon(scores),
                )
            }

    /**
     * The game's winners: everyone tied on the best total (or whoever won outright), and nobody
     * before a shot has scored. More than one entry is a tie.
     */
    val winners: List<Player>
        get() {
            val all = standings
            all.filter { it.hasWon }.takeIf { it.isNotEmpty() }?.let { outright -> return outright.map { it.player } }
            val totals = all.mapNotNull { standing -> standing.total?.let { standing.player to it } }
            val best =
                when (mode.ranking) {
                    Ranking.LOWER_WINS -> totals.minOfOrNull { it.second }
                    Ranking.HIGHER_WINS -> totals.maxOfOrNull { it.second }
                } ?: return emptyList()
            return totals.filter { it.second == best }.map { it.first }
        }

    /** All shots are in and scored, or someone won outright. */
    val isComplete: Boolean
        get() {
            if (standings.any { it.hasWon }) return true
            val quota = mode.shotsPerPlayer ?: return false
            return players.all { attributionsOf(it.id).size >= quota } && attributions.all { it.score != null }
        }

    companion object {
        fun new(config: GameConfig): GameState = GameState(config = config)
    }
}

/**
 * One player's line on the scoreboard.
 *
 * @property shotsTaken swings attributed to them, scored or not.
 * @property total their [GameMode.total], or `null` before a shot has scored.
 */
data class PlayerStanding(
    val player: Player,
    val shotsTaken: Int,
    val scores: List<ShotScore>,
    val total: Double?,
    val hasWon: Boolean,
)

/** What can happen to a game. Every timestamp comes from outside; the reducer has no clock. */
sealed interface GameEvent {
    data class Start(
        val atEpochMillis: Long,
    ) : GameEvent

    /** A swing's `event_id` appeared for the first time (provisional or not): it's attributed now. */
    data class ShotSighted(
        val eventId: String,
    ) : GameEvent

    /** A swing's final version: scored for whoever it was attributed to (attributed now if unseen). */
    data class ShotFinal(
        val shot: GameShot,
    ) : GameEvent

    /** Takes back the last attribution ("wrong person hit"); that player is up again. */
    data object Undo : GameEvent

    /** Passes the turn to the next player with shots left. */
    data object Skip : GameEvent

    data object Pause : GameEvent

    data object Resume : GameEvent

    data class End(
        val atEpochMillis: Long,
    ) : GameEvent
}

/**
 * The game's state machine: `(state, event) → state`, deterministic and pure (plan F9). Events that
 * don't apply in the current status leave the state unchanged.
 *
 * Attribution (A14): a swing belongs to whoever's turn it is when its `event_id` is first
 * sighted, and the turn moves on right then, so the next player's swing inside the 20 s
 * `shot_update` window can't steal it. It is scored when its final version arrives.
 */
@Suppress("ReturnCount") // Guard clauses: each unmet precondition returns the state unchanged.
object GameSessionReducer {
    fun reduce(
        state: GameState,
        event: GameEvent,
    ): GameState =
        when (event) {
            is GameEvent.Start -> {
                start(state, event.atEpochMillis)
            }

            is GameEvent.ShotSighted -> {
                sight(state, event.eventId)
            }

            is GameEvent.ShotFinal -> {
                final(state, event.shot)
            }

            GameEvent.Undo -> {
                undo(state)
            }

            GameEvent.Skip -> {
                skip(state)
            }

            GameEvent.Pause -> {
                if (state.status ==
                    GameStatus.IN_PROGRESS
                ) {
                    state.copy(status = GameStatus.PAUSED)
                } else {
                    state
                }
            }

            GameEvent.Resume -> {
                if (state.status ==
                    GameStatus.PAUSED
                ) {
                    state.copy(status = GameStatus.IN_PROGRESS)
                } else {
                    state
                }
            }

            is GameEvent.End -> {
                end(state, event.atEpochMillis)
            }
        }

    /** Folds [events] over a new game of [config]. */
    fun replay(
        config: GameConfig,
        events: List<GameEvent>,
    ): GameState = events.fold(GameState.new(config), ::reduce)

    private fun start(
        state: GameState,
        at: Long,
    ): GameState {
        if (state.status != GameStatus.NOT_STARTED) return state
        return state.copy(status = GameStatus.IN_PROGRESS, turn = TurnPosition(0), startedAtEpochMillis = at)
    }

    private fun sight(
        state: GameState,
        eventId: String,
    ): GameState {
        if (!state.isAccepting || eventId in state.ignoredEventIds) return state
        if (state.attributions.any { it.eventId == eventId }) return state
        val turn = state.turn ?: return state
        val player = state.players[turn.playerIndex]
        val shotIndex = state.attributionsOf(player.id).size
        val attribution =
            Attribution(
                eventId = eventId,
                playerId = player.id,
                playerShotIndex = shotIndex,
                target = state.mode.target(shotIndex, state.scoresOf(player.id)),
                turnBefore = turn,
            )
        val attributed = state.copy(attributions = state.attributions + attribution)
        return attributed.copy(turn = advance(attributed, turn.copy(shotsThisTurn = turn.shotsThisTurn + 1)))
    }

    private fun final(
        state: GameState,
        shot: GameShot,
    ): GameState {
        if (state.status !in SCORING || shot.eventId in state.ignoredEventIds) return state
        // A v1 shot is final as it arrives; its first sighting may come after this, or not at all.
        val sighted =
            if (state.attributions.none { it.eventId == shot.eventId }) sight(state, shot.eventId) else state
        val index = sighted.attributions.indexOfFirst { it.eventId == shot.eventId }
        if (index < 0) return state
        val attribution = sighted.attributions[index]
        if (attribution.score != null) return sighted
        val score = sighted.mode.score(shot, attribution.target, sighted.scoresOf(attribution.playerId))
        val scored =
            sighted.copy(
                attributions =
                    sighted.attributions.toMutableList().also {
                        it[index] = attribution.copy(shot = shot, score = score)
                    },
            )
        return if (scored.isComplete) scored.copy(status = GameStatus.COMPLETE, turn = null) else scored
    }

    private fun undo(state: GameState): GameState {
        if (state.status !in UNDOABLE) return state
        val last = state.attributions.lastOrNull() ?: return state
        return state.copy(
            status = if (state.status == GameStatus.COMPLETE) GameStatus.IN_PROGRESS else state.status,
            turn = last.turnBefore,
            attributions = state.attributions.dropLast(1),
            ignoredEventIds = state.ignoredEventIds + last.eventId,
        )
    }

    private fun skip(state: GameState): GameState {
        if (state.status != GameStatus.IN_PROGRESS) return state
        val turn = state.turn ?: return state
        return state.copy(turn = advance(state, turn.copy(shotsThisTurn = state.config.turnOrder.shotsPerTurn)))
    }

    private fun end(
        state: GameState,
        at: Long,
    ): GameState {
        if (state.status == GameStatus.NOT_STARTED || state.status == GameStatus.ENDED) return state
        return state.copy(status = GameStatus.ENDED, turn = null, endedAtEpochMillis = at)
    }

    /**
     * The turn after [turn]: the same player while their turn and shots last, otherwise the next
     * player (round robin) with shots left; `null` when nobody has any.
     */
    private fun advance(
        state: GameState,
        turn: TurnPosition,
    ): TurnPosition? {
        val players = state.players
        val quota = state.mode.shotsPerPlayer

        fun hasShotsLeft(index: Int) = quota == null || state.attributionsOf(players[index].id).size < quota
        if (turn.shotsThisTurn < state.config.turnOrder.shotsPerTurn && hasShotsLeft(turn.playerIndex)) return turn
        return (1..players.size)
            .map { (turn.playerIndex + it) % players.size }
            .firstOrNull(::hasShotsLeft)
            ?.let { TurnPosition(it) }
    }

    private val SCORING = setOf(GameStatus.IN_PROGRESS, GameStatus.PAUSED)
    private val UNDOABLE = setOf(GameStatus.IN_PROGRESS, GameStatus.PAUSED, GameStatus.COMPLETE)
}
