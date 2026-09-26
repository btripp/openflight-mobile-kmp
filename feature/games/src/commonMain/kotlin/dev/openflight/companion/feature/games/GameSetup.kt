// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import kotlin.random.Random

/**
 * What the games screen opens on (plan A6): a typed navigation argument, e.g. "play a hole" opens
 * [GameType.CLOSEST_TO_PIN] at the hole's distance. Both `null` opens the default setup.
 */
data class GameLaunch(
    val type: GameType? = null,
    val distanceYards: Double? = null,
) {
    companion object {
        /** From a route's primitive arguments (Android `Games(mode, distanceYards)`, the iOS destination). */
        fun fromRoute(
            mode: String?,
            distanceYards: Double?,
        ) = GameLaunch(GameType.fromStorageValue(mode), distanceYards?.takeIf { it.isFinite() && it > 0 })
    }
}

/** How a [GameType.TARGET_CALLOUT] picks its targets. */
enum class TargetPlanKind {
    FIXED,
    RANDOM,
    LADDER,
}

/**
 * The setup sheet's draft: every mode's settings, so switching modes keeps what was typed.
 *
 * @property targetYards the fixed call-out target, the pin, or the bullseye.
 * @property shotsPerPlayer shots each for every mode but the ladder (its length) and Golf Pong.
 */
data class GameSetup(
    val type: GameType = GameType.TARGET_CALLOUT,
    val players: List<Player> = listOf(defaultPlayer(0)),
    val shotsPerTurn: Int = 1,
    val targetYards: Double = DEFAULT_TARGET_YARDS,
    val pinLateralYards: Double = 0.0,
    val planKind: TargetPlanKind = TargetPlanKind.FIXED,
    val randomMinYards: Double = DEFAULT_RANDOM_MIN_YARDS,
    val randomMaxYards: Double = DEFAULT_RANDOM_MAX_YARDS,
    val metric: DistanceMetric = DistanceMetric.CARRY,
    val shotsPerPlayer: Int = DEFAULT_SHOTS_PER_PLAYER,
    val pongCups: Int = GameMode.GolfPong.DEFAULT_CUPS,
    val iconicShotId: String = IconicShotCatalog.shots.first().id,
) {
    val canAddPlayer: Boolean get() = players.size < GameConfig.MAX_PLAYERS

    /** The game this draft describes; a random target sequence is drawn from [random] now. */
    fun toConfig(random: Random = Random.Default): GameConfig {
        val mode =
            when (type) {
                GameType.TARGET_CALLOUT -> {
                    GameMode.TargetCallout.of(targetPlan(), metric, random)
                }

                GameType.CLOSEST_TO_PIN -> {
                    GameMode.ClosestToPin(targetYards, pinLateralYards, shotsPerPlayer)
                }

                GameType.BULLSEYE -> {
                    GameMode.Bullseye(targetYards, shotsPerPlayer)
                }

                GameType.GOLF_PONG -> {
                    GameMode.GolfPong.standard(cupCount = pongCups)
                }

                GameType.ICONIC_SHOTS -> {
                    GameMode.IconicShots(
                        IconicShotCatalog.find(iconicShotId) ?: IconicShotCatalog.shots.first(),
                        shotsPerPlayer,
                    )
                }
            }
        return GameConfig(mode, players, TurnOrder(shotsPerTurn))
    }

    private fun targetPlan(): TargetPlan =
        when (planKind) {
            TargetPlanKind.FIXED -> TargetPlan.Fixed(targetYards, shotsPerPlayer)
            TargetPlanKind.RANDOM -> TargetPlan.RandomSequence(randomMinYards, randomMaxYards, shotsPerPlayer)
            TargetPlanKind.LADDER -> TargetPlan.Ladder()
        }

    companion object {
        const val DEFAULT_TARGET_YARDS = 80.0
        const val DEFAULT_RANDOM_MIN_YARDS = 50.0
        const val DEFAULT_RANDOM_MAX_YARDS = 150.0
        const val DEFAULT_SHOTS_PER_PLAYER = 5
        const val MIN_TARGET_YARDS = 5.0
        const val MAX_TARGET_YARDS = 400.0
        const val MAX_SHOTS_PER_PLAYER = 20

        /** "Player 1", "Player 2", ... with the matching colour slot. */
        fun defaultPlayer(index: Int) = Player(id = "p${index + 1}", name = "Player ${index + 1}", colorIndex = index)

        /** The setup a [launch] asks for: its mode, with its distance as the target or pin. */
        fun from(launch: GameLaunch?): GameSetup {
            val setup = GameSetup(type = launch?.type ?: GameType.TARGET_CALLOUT)
            val distance = launch?.distanceYards?.coerceIn(MIN_TARGET_YARDS, MAX_TARGET_YARDS) ?: return setup
            return setup.copy(targetYards = distance)
        }
    }
}
