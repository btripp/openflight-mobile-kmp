// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** The kinds of game, and the [storageValue] each is filed under as an activity type. */
enum class GameType(
    val storageValue: String,
    val title: String,
) {
    TARGET_CALLOUT("TARGET_CALLOUT", "Target call-out"),
    CLOSEST_TO_PIN("CLOSEST_TO_PIN", "Closest to the pin"),
    BULLSEYE("BULLSEYE", "Bullseye"),
    GOLF_PONG("GOLF_PONG", "Golf Pong"),
    ICONIC_SHOTS("ICONIC_SHOTS", "Iconic shots"),
    ;

    companion object {
        fun fromStorageValue(value: String?): GameType? = entries.firstOrNull { it.storageValue == value }
    }
}

/** Which way a player's [GameMode.total] ranks. */
enum class Ranking {
    /** Yards off: the smallest total wins. */
    LOWER_WINS,

    /** Points: the largest total wins. */
    HIGHER_WINS,
}

/** What [GameMode.TargetCallout] measures. */
enum class DistanceMetric {
    CARRY,

    /** Carry + estimated roll: always an estimate. */
    TOTAL,
}

/**
 * The rules of one game (plan F9): the target each shot aims at, how a shot scores, how a
 * player's shots add up and when someone has won outright. Pure and deterministic: anything
 * random (a [TargetPlan.Random] sequence) is resolved when the mode is built.
 *
 * A player's `previous` scores are theirs only, oldest first.
 */
sealed interface GameMode {
    val type: GameType
    val ranking: Ranking

    /** Shots each player takes, or `null` for a game that runs until someone wins ([GolfPong]). */
    val shotsPerPlayer: Int?

    /** The target of a player's shot number [shotIndex] (from 0), given their scored shots. */
    fun target(
        shotIndex: Int,
        previous: List<ShotScore>,
    ): GameTarget

    /** How [shot], aimed at [target], scores after the player's [previous] shots. */
    fun score(
        shot: GameShot,
        target: GameTarget,
        previous: List<ShotScore>,
    ): ShotScore

    /** A player's standing over their scored shots, or `null` before they've scored one. */
    fun total(scores: List<ShotScore>): Double?

    /** Whether these scores win the game outright, ending it early. */
    fun hasWon(scores: List<ShotScore>): Boolean = false

    /**
     * Hit a target distance: fixed, a random sequence or a ladder. A shot scores |metric − target|
     * yards and a player's total is the average, so lower wins.
     *
     * @property targets one target per shot (every player's n-th shot aims at `targets[n]`).
     */
    data class TargetCallout(
        val targets: List<Double>,
        val metric: DistanceMetric = DistanceMetric.CARRY,
        val plan: TargetPlan = TargetPlan.Fixed(targets.firstOrNull() ?: 0.0, targets.size),
    ) : GameMode {
        init {
            require(targets.isNotEmpty()) { "A target call-out needs at least one target." }
        }

        override val type: GameType get() = GameType.TARGET_CALLOUT
        override val ranking: Ranking get() = Ranking.LOWER_WINS
        override val shotsPerPlayer: Int get() = targets.size

        override fun target(
            shotIndex: Int,
            previous: List<ShotScore>,
        ) = GameTarget(targets[shotIndex.coerceIn(targets.indices)])

        override fun score(
            shot: GameShot,
            target: GameTarget,
            previous: List<ShotScore>,
        ): ShotScore {
            // TOTAL falls back to carry for the rare shot that can't be flown; both are labelled.
            val total = shot.totalYards
            val value = if (metric == DistanceMetric.TOTAL && total != null) total else shot.carryYards
            val estimated = (metric == DistanceMetric.TOTAL && total != null) || shot.carryEstimated
            val delta = value - target.distanceYards
            return ShotScore(points = abs(delta), deltaYards = delta, metricEstimated = estimated)
        }

        override fun total(scores: List<ShotScore>): Double? =
            scores.takeIf { it.isNotEmpty() }?.map { it.points }?.average()

        companion object {
            /** Resolves [plan]'s targets, drawing a random sequence from [random]. */
            fun of(
                plan: TargetPlan,
                metric: DistanceMetric = DistanceMetric.CARRY,
                random: Random = Random.Default,
            ) = TargetCallout(targets = plan.targets(random), metric = metric, plan = plan)
        }
    }

    /**
     * Land it nearest a pin at [pinYards], [pinLateralYards] off the line. A shot scores its
     * straight-line miss in yards; without a side measurement only the distance counts
     * ([ShotScore.lateralUnknown]). A player's total is their best shot.
     */
    data class ClosestToPin(
        val pinYards: Double,
        val pinLateralYards: Double = 0.0,
        override val shotsPerPlayer: Int = DEFAULT_SHOTS,
    ) : GameMode {
        init {
            require(shotsPerPlayer > 0) { "Each player needs at least one shot." }
        }

        override val type: GameType get() = GameType.CLOSEST_TO_PIN
        override val ranking: Ranking get() = Ranking.LOWER_WINS

        override fun target(
            shotIndex: Int,
            previous: List<ShotScore>,
        ) = GameTarget(pinYards, pinLateralYards)

        override fun score(
            shot: GameShot,
            target: GameTarget,
            previous: List<ShotScore>,
        ): ShotScore {
            val miss = missYards(shot, target)
            return ShotScore(
                points = miss.yards,
                deltaYards = miss.yards,
                lateralUnknown = miss.lateralUnknown,
                metricEstimated = shot.carryEstimated,
            )
        }

        override fun total(scores: List<ShotScore>): Double? = scores.minOfOrNull { it.points }
    }

    /**
     * Rings around a target at [targetYards]: a shot inside the n-th ring scores its points
     * (5/10/20/30 yd give 10/5/3/1 by default), outside them 0. A player's total is the sum.
     */
    data class Bullseye(
        val targetYards: Double,
        override val shotsPerPlayer: Int = DEFAULT_SHOTS,
        val rings: List<BullseyeRing> = BullseyeRing.DEFAULT,
    ) : GameMode {
        init {
            require(shotsPerPlayer > 0) { "Each player needs at least one shot." }
            require(rings.isNotEmpty()) { "A bullseye needs at least one ring." }
        }

        override val type: GameType get() = GameType.BULLSEYE
        override val ranking: Ranking get() = Ranking.HIGHER_WINS

        override fun target(
            shotIndex: Int,
            previous: List<ShotScore>,
        ) = GameTarget(targetYards)

        override fun score(
            shot: GameShot,
            target: GameTarget,
            previous: List<ShotScore>,
        ): ShotScore {
            val miss = missYards(shot, target)
            val ring = rings.sortedBy { it.radiusYards }.firstOrNull { miss.yards <= it.radiusYards }
            return ShotScore(
                points = ring?.points?.toDouble() ?: 0.0,
                deltaYards = miss.yards,
                lateralUnknown = miss.lateralUnknown,
                metricEstimated = shot.carryEstimated,
            )
        }

        override fun total(scores: List<ShotScore>): Double? = scores.takeIf { it.isNotEmpty() }?.sumOf { it.points }
    }

    /**
     * Cups at distinct distances, each with a radius tolerance: a shot within a cup's radius sinks
     * it (the nearest one still standing), and the first player to sink all of their cups wins.
     * Players alternate; every player has their own set of cups.
     */
    data class GolfPong(
        val cups: List<PongCup>,
    ) : GameMode {
        init {
            require(cups.isNotEmpty()) { "Golf Pong needs at least one cup." }
            require(cups.map { it.distanceYards }.distinct().size == cups.size) { "Cups need distinct distances." }
        }

        override val type: GameType get() = GameType.GOLF_PONG
        override val ranking: Ranking get() = Ranking.HIGHER_WINS
        override val shotsPerPlayer: Int? get() = null

        /** The nearest cup still standing, so the call-out has one distance to say. */
        override fun target(
            shotIndex: Int,
            previous: List<ShotScore>,
        ): GameTarget {
            val sunk = previous.mapNotNullTo(mutableSetOf()) { it.sunkCupIndex }
            val standing = cups.indices.firstOrNull { it !in sunk } ?: cups.lastIndex
            return GameTarget(cups[standing].distanceYards)
        }

        override fun score(
            shot: GameShot,
            target: GameTarget,
            previous: List<ShotScore>,
        ): ShotScore {
            val sunk = previous.mapNotNullTo(mutableSetOf()) { it.sunkCupIndex }
            val nearest =
                cups.indices
                    .filter { it !in sunk }
                    .map { index -> index to missYards(shot, GameTarget(cups[index].distanceYards)) }
                    .minByOrNull { (_, miss) -> miss.yards }
                    ?: return ShotScore(points = 0.0, deltaYards = null, metricEstimated = shot.carryEstimated)
            val (index, miss) = nearest
            val hit = miss.yards <= cups[index].radiusYards
            return ShotScore(
                points = if (hit) 1.0 else 0.0,
                deltaYards = miss.yards,
                lateralUnknown = miss.lateralUnknown,
                metricEstimated = shot.carryEstimated,
                sunkCupIndex = index.takeIf { hit },
            )
        }

        override fun total(scores: List<ShotScore>): Double? =
            scores.takeIf { it.isNotEmpty() }?.count { it.sunkCupIndex != null }?.toDouble()

        override fun hasWon(scores: List<ShotScore>): Boolean =
            scores.mapNotNullTo(mutableSetOf()) { it.sunkCupIndex }.size == cups.size

        companion object {
            const val DEFAULT_CUPS = 6
            const val LARGE_CUPS = 10
            const val DEFAULT_NEAR_YARDS = 50.0
            const val DEFAULT_FAR_YARDS = 150.0
            const val DEFAULT_RADIUS_YARDS = 5.0

            /** [cupCount] cups spread evenly from [nearYards] to [farYards], nearest first. */
            fun standard(
                cupCount: Int = DEFAULT_CUPS,
                nearYards: Double = DEFAULT_NEAR_YARDS,
                farYards: Double = DEFAULT_FAR_YARDS,
                radiusYards: Double = DEFAULT_RADIUS_YARDS,
            ): GolfPong {
                require(cupCount >= 2) { "Golf Pong needs at least two cups." }
                require(farYards > nearYards) { "The far cup must be beyond the near one." }
                val step = (farYards - nearYards) / (cupCount - 1)
                return GolfPong(List(cupCount) { PongCup(nearYards + it * step, radiusYards) })
            }
        }
    }

    /**
     * Recreate a famous kind of shot ([IconicShot]): each attempt scores 0–100 on how close its
     * carry, curve and apex come, and a player's total is their best attempt.
     */
    data class IconicShots(
        val shot: IconicShot,
        override val shotsPerPlayer: Int = DEFAULT_SHOTS,
    ) : GameMode {
        init {
            require(shotsPerPlayer > 0) { "Each player needs at least one shot." }
        }

        override val type: GameType get() = GameType.ICONIC_SHOTS
        override val ranking: Ranking get() = Ranking.HIGHER_WINS

        override fun target(
            shotIndex: Int,
            previous: List<ShotScore>,
        ) = GameTarget(shot.targetCarryYards, shot.curveYards)

        override fun score(
            shot: GameShot,
            target: GameTarget,
            previous: List<ShotScore>,
        ): ShotScore {
            val scored =
                mutableListOf(IconicWeight.CARRY to closeness(shot.carryYards - target.distanceYards, CARRY_TOLERANCE))
            val offline = shot.offlineYards
            if (offline !=
                null
            ) {
                scored += IconicWeight.CURVE to closeness(offline - target.lateralYards, CURVE_TOLERANCE)
            }
            val wantedApex = this.shot.apexYards
            val apex = shot.apexYards
            if (wantedApex != null &&
                apex != null
            ) {
                scored += IconicWeight.APEX to closeness(apex - wantedApex, APEX_TOLERANCE)
            }
            val weight = scored.sumOf { (dimension, _) -> dimension.weight }
            val closeness = scored.sumOf { (dimension, value) -> dimension.weight * value } / weight
            return ShotScore(
                points = (closeness * MAX_POINTS).roundToInt().toDouble(),
                deltaYards = shot.carryYards - target.distanceYards,
                lateralUnknown = offline == null,
                metricEstimated = shot.carryEstimated || (wantedApex != null && apex != null),
            )
        }

        override fun total(scores: List<ShotScore>): Double? = scores.maxOfOrNull { it.points }

        /** 1 on the number, falling linearly to 0 at [tolerance] yards off. */
        private fun closeness(
            errorYards: Double,
            tolerance: Double,
        ) = max(0.0, 1.0 - abs(errorYards) / tolerance)

        private enum class IconicWeight(
            val weight: Double,
        ) {
            CARRY(CARRY_WEIGHT),
            CURVE(CURVE_WEIGHT),
            APEX(APEX_WEIGHT),
        }

        companion object {
            const val MAX_POINTS = 100
            const val CARRY_TOLERANCE = 20.0
            const val CURVE_TOLERANCE = 15.0
            const val APEX_TOLERANCE = 15.0

            /** Carry matters most, then the shape, then the height. */
            const val CARRY_WEIGHT = 0.5
            const val CURVE_WEIGHT = 0.3
            const val APEX_WEIGHT = 0.2
        }
    }

    companion object {
        const val DEFAULT_SHOTS = 3
    }
}

/** A [GameMode.Bullseye] ring: a shot within [radiusYards] of the target scores [points]. */
data class BullseyeRing(
    val radiusYards: Double,
    val points: Int,
) {
    companion object {
        val DEFAULT =
            listOf(
                BullseyeRing(5.0, 10),
                BullseyeRing(10.0, 5),
                BullseyeRing(20.0, 3),
                BullseyeRing(30.0, 1),
            )
    }
}

/** A [GameMode.GolfPong] cup on the target line. */
data class PongCup(
    val distanceYards: Double,
    val radiusYards: Double = GameMode.GolfPong.DEFAULT_RADIUS_YARDS,
)

/** How a [GameMode.TargetCallout] picks its targets. */
sealed interface TargetPlan {
    /** One target per shot. */
    fun targets(random: Random): List<Double>

    /** The same target for every one of [shots] shots, e.g. 80 yd. */
    data class Fixed(
        val yards: Double,
        val shots: Int,
    ) : TargetPlan {
        override fun targets(random: Random) = List(shots) { yards }
    }

    /** [shots] targets drawn from [minYards]..[maxYards] in [stepYards] steps. */
    data class RandomSequence(
        val minYards: Double,
        val maxYards: Double,
        val shots: Int,
        val stepYards: Double = DEFAULT_RANDOM_STEP,
    ) : TargetPlan {
        override fun targets(random: Random): List<Double> {
            val steps = ((maxYards - minYards) / stepYards).toInt()
            return List(shots) { minYards + random.nextInt(steps + 1) * stepYards }
        }
    }

    /** Climb from [startYards] to [endYards] by [stepYards]: 60 → 140 by 10 is 9 shots. */
    data class Ladder(
        val startYards: Double = DEFAULT_LADDER_START,
        val endYards: Double = DEFAULT_LADDER_END,
        val stepYards: Double = DEFAULT_LADDER_STEP,
    ) : TargetPlan {
        override fun targets(random: Random): List<Double> {
            val count = ((endYards - startYards) / stepYards).toInt() + 1
            return List(count) { startYards + it * stepYards }
        }
    }

    companion object {
        const val DEFAULT_RANDOM_STEP = 5.0
        const val DEFAULT_LADDER_START = 60.0
        const val DEFAULT_LADDER_END = 140.0
        const val DEFAULT_LADDER_STEP = 10.0
    }
}

/** A straight-line miss, or a distance-only one when the shot had no side measurement. */
internal data class Miss(
    val yards: Double,
    val lateralUnknown: Boolean,
)

internal fun missYards(
    shot: GameShot,
    target: GameTarget,
): Miss {
    val long = shot.carryYards - target.distanceYards
    val offline = shot.offlineYards ?: return Miss(abs(long), lateralUnknown = true)
    return Miss(hypot(long, offline - target.lateralYards), lateralUnknown = false)
}
