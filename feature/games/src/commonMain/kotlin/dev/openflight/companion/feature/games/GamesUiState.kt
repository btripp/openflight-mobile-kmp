// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/**
 * The games screen (plan F9): set up a game, play it, see the result. Games count only real
 * shots, so without a connected Pi there's nothing to play ([ConnectToPlay]).
 */
sealed interface GamesUiState {
    val units: UnitSystem

    /** The debug "Simulate shot" button (a `--mock` Pi only), or `null` when hidden. */
    val simulate: SimulateAction?

    /** Not connected and no game running: the setup waits behind a "Connect to play" message. */
    data class ConnectToPlay(
        val setup: GameSetup,
        override val units: UnitSystem,
        override val simulate: SimulateAction? = null,
        val message: String = GameCopy.CONNECT_TO_PLAY,
    ) : GamesUiState

    /** The setup sheet, ready to start. */
    data class Setup(
        val setup: GameSetup,
        override val units: UnitSystem,
        override val simulate: SimulateAction? = null,
        val iconicShots: List<IconicShot> = IconicShotCatalog.shots,
    ) : GamesUiState

    /**
     * A game in progress (or complete, waiting for End).
     *
     * @property connected `false` while the link is down mid-game: the game keeps its state, but
     *   no shots arrive until it's back.
     */
    data class Playing(
        val game: GameView,
        override val units: UnitSystem,
        val connected: Boolean,
        override val simulate: SimulateAction? = null,
        val error: String? = null,
    ) : GamesUiState

    /** The finished game. [saved] once it's in the Activities history. */
    data class Results(
        val game: GameView,
        override val units: UnitSystem,
        val headline: String,
        val saved: Boolean,
    ) : GamesUiState {
        override val simulate: SimulateAction? get() = null
    }
}

/** The "Simulate shot" action and why it can't run right now, if it can't. */
data class SimulateAction(
    val availability: PiFeatureAvailability,
)

/**
 * A game, ready to draw.
 *
 * @property currentPlayer whose turn it is, or `null` when nobody has shots left.
 * @property targetYards the current player's target, or `null` with nobody up.
 * @property lastShot the most recent scored shot, for the big delta.
 * @property scoreboard one row per player, in player order.
 * @property dots every scored shot around its own target, for the mini top-down view.
 */
data class GameView(
    val title: String,
    val type: GameType,
    val status: GameStatus,
    val currentPlayer: Player?,
    val turnLabel: String?,
    val targetYards: Double?,
    val targetLabel: String?,
    val lastShot: LastShotView?,
    val scoreboard: List<ScoreboardRow>,
    val dots: List<TargetDot>,
    val winners: List<Player>,
    val canUndo: Boolean,
) {
    val isPaused: Boolean get() = status == GameStatus.PAUSED
    val isComplete: Boolean get() = status == GameStatus.COMPLETE
}

/**
 * @property deltaYards signed (long positive) in a target call-out, otherwise the miss distance.
 * @property estimated the scored number was an estimate: show "est.".
 * @property lateralUnknown the shot had no side measurement: distance only.
 */
data class LastShotView(
    val player: Player,
    val label: String,
    val deltaYards: Double?,
    val estimated: Boolean,
    val lateralUnknown: Boolean,
)

/**
 * @property shotsLabel "2 / 5" (or "3" in an open-ended game).
 * @property totalLabel the player's total in the mode's terms.
 */
data class ScoreboardRow(
    val player: Player,
    val shotsLabel: String,
    val totalLabel: String,
    val isCurrent: Boolean,
    val isWinner: Boolean,
)

/**
 * A scored shot relative to its target, in yards: [longYards] past it (negative short) and
 * [offlineYards] right of it, `null` when the side wasn't measured.
 */
data class TargetDot(
    val eventId: String,
    val colorIndex: Int,
    val longYards: Double,
    val offlineYards: Double?,
)

/** User intents from the games screen, sent up to [GamesViewModel.onEvent]. */
sealed interface GamesEvent {
    data class SelectType(
        val type: GameType,
    ) : GamesEvent

    data class SetTargetYards(
        val yards: Double,
    ) : GamesEvent

    data class SetPinLateralYards(
        val yards: Double,
    ) : GamesEvent

    data class SetPlanKind(
        val kind: TargetPlanKind,
    ) : GamesEvent

    data class SetRandomRange(
        val minYards: Double,
        val maxYards: Double,
    ) : GamesEvent

    data class SetMetric(
        val metric: DistanceMetric,
    ) : GamesEvent

    data class SetShotsPerPlayer(
        val shots: Int,
    ) : GamesEvent

    data class SetShotsPerTurn(
        val shots: Int,
    ) : GamesEvent

    data class SetPongCups(
        val cups: Int,
    ) : GamesEvent

    data class SelectIconicShot(
        val id: String,
    ) : GamesEvent

    data object AddPlayer : GamesEvent

    data class RenamePlayer(
        val playerId: String,
        val name: String,
    ) : GamesEvent

    data class RemovePlayer(
        val playerId: String,
    ) : GamesEvent

    data object Start : GamesEvent

    /** "Undo last attribution": the wrong person hit. */
    data object Undo : GamesEvent

    data object Skip : GamesEvent

    data object Pause : GamesEvent

    data object Resume : GamesEvent

    /** Ends the game and files it in the Activities history. */
    data object End : GamesEvent

    /** From the results, back to a fresh setup with the same settings. */
    data object PlayAgain : GamesEvent

    /** Asks a `--mock` Pi for a fake shot (`simulate_shot`, Wi-Fi only). */
    data object SimulateShot : GamesEvent

    data object DismissError : GamesEvent
}

/** One-shot effects of the games screen. */
sealed interface GamesEffect {
    /** The finished game was filed as activity [activityId]. */
    data class Saved(
        val activityId: String,
    ) : GamesEffect
}
