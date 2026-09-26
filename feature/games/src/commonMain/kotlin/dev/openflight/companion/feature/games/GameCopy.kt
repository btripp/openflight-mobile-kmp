// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.convertDistanceFromYards
import dev.openflight.companion.core.insights.distanceUnitLabel
import dev.openflight.companion.core.model.ShotMetricFormatter
import kotlin.math.abs

/** Games wording shared by both platforms, so Android and iOS say the same thing. */
object GameCopy {
    const val ON_TARGET: String = "On target"
    const val NO_SCORE: String = "No score"
    const val CONNECT_TO_PLAY: String = "Connect to your OpenFlight to play. Games only count real shots."

    /** Under half a unit off counts as on the number. */
    private const val ON_TARGET_UNITS = 0.5

    /** The game's name with its target, e.g. "Target call-out · 80 yds" or "Target call-out · ladder 60–140 yds". */
    fun title(
        mode: GameMode,
        units: UnitSystem,
    ): String {
        val detail =
            when (mode) {
                is GameMode.TargetCallout -> {
                    when (val plan = mode.plan) {
                        is TargetPlan.Fixed -> {
                            distance(plan.yards, units)
                        }

                        is TargetPlan.Ladder -> {
                            "ladder ${number(plan.startYards, units)}–${distance(plan.endYards, units)}"
                        }

                        is TargetPlan.RandomSequence -> {
                            "random ${number(plan.minYards, units)}–${distance(plan.maxYards, units)}"
                        }
                    } + if (mode.metric == DistanceMetric.TOTAL) " total (est.)" else ""
                }

                is GameMode.ClosestToPin -> {
                    distance(mode.pinYards, units)
                }

                is GameMode.Bullseye -> {
                    distance(mode.targetYards, units)
                }

                is GameMode.GolfPong -> {
                    "${mode.cups.size} cups"
                }

                is GameMode.IconicShots -> {
                    mode.shot.title
                }
            }
        return "${mode.type.title} · $detail"
    }

    /** One shot's result: "3 yds long", "4.2 yds from the pin", "10 pts", "Sank the 90 yds cup!", "82 / 100". */
    fun scoreLabel(
        mode: GameMode,
        score: ShotScore,
        units: UnitSystem,
    ): String {
        val delta = score.deltaYards
        return when (mode) {
            is GameMode.TargetCallout -> {
                longOrShort(delta ?: 0.0, units)
            }

            is GameMode.ClosestToPin -> {
                "${distance(delta ?: 0.0, units, 1)} from the pin"
            }

            is GameMode.Bullseye -> {
                points(score.points)
            }

            is GameMode.GolfPong -> {
                pongLabel(mode, score, units)
            }

            is GameMode.IconicShots -> {
                "${score.points.toInt()} / ${GameMode.IconicShots.MAX_POINTS}"
            }
        } + if (score.lateralUnknown && mode.usesSide) " (distance only)" else ""
    }

    /** A player's total: "4.2 yds" (average or best miss), "23 pts", "4 of 6 cups", "82 / 100". */
    fun totalLabel(
        mode: GameMode,
        total: Double?,
        units: UnitSystem,
    ): String {
        if (total == null) return ShotMetricFormatter.MISSING
        return when (mode) {
            is GameMode.TargetCallout, is GameMode.ClosestToPin -> distance(total, units, 1)
            is GameMode.Bullseye -> points(total)
            is GameMode.GolfPong -> "${total.toInt()} of ${mode.cups.size} cups"
            is GameMode.IconicShots -> "${total.toInt()} / ${GameMode.IconicShots.MAX_POINTS}"
        }
    }

    /**
     * The activity card's big text: a solo player's total, otherwise who won ("Ann won!",
     * "Tie: Ann & Bob"), or [NO_SCORE] before anything scored.
     */
    fun headline(
        state: GameState,
        units: UnitSystem,
    ): String {
        val winners = state.winners
        return when {
            winners.isEmpty() -> NO_SCORE
            state.players.size == 1 -> totalLabel(state.mode, state.standings.single().total, units)
            winners.size == 1 -> "${winners.single().name} won!"
            else -> "Tie: " + winners.joinToString(" & ") { it.name }
        }
    }

    /** "Ann's turn", for the banner. */
    fun turnLabel(player: Player): String = "${player.name}'s turn"

    /** "80 yds" (or "73 m"). */
    fun distance(
        yards: Double,
        units: UnitSystem,
        decimals: Int = 0,
    ): String = number(yards, units, decimals) + " " + distanceUnitLabel(units)

    private fun number(
        yards: Double,
        units: UnitSystem,
        decimals: Int = 0,
    ): String = ShotMetricFormatter.number(convertDistanceFromYards(yards, units), decimals)

    private fun points(value: Double): String = "${value.toInt()} pts"

    /** "3.0 yds long", "4.5 yds short" or [ON_TARGET]. */
    private fun longOrShort(
        deltaYards: Double,
        units: UnitSystem,
    ): String =
        when {
            abs(convertDistanceFromYards(deltaYards, units)) < ON_TARGET_UNITS -> ON_TARGET
            deltaYards > 0 -> "${distance(deltaYards, units, 1)} long"
            else -> "${distance(-deltaYards, units, 1)} short"
        }

    /** "Sank the 90 yds cup!", "Miss by 12.0 yds" or [NO_SCORE]. */
    private fun pongLabel(
        mode: GameMode.GolfPong,
        score: ShotScore,
        units: UnitSystem,
    ): String =
        score.sunkCupIndex?.let { "Sank the ${distance(mode.cups[it].distanceYards, units)} cup!" }
            ?: score.deltaYards?.let { "Miss by ${distance(it, units, 1)}" }
            ?: NO_SCORE

    /** Whether the mode's miss can be two-dimensional (TargetCallout only measures distance). */
    private val GameMode.usesSide: Boolean get() = this !is GameMode.TargetCallout
}
