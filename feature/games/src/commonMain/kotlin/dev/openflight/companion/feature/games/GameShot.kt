// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

/**
 * One shot as the game engine sees it: already measured and estimated, so the engine stays pure
 * (no flight simulation, no clock). The ViewModel builds it from a live shot
 * ([GameShotMeasurer]).
 *
 * @property eventId the shot's `event_id`: the key that ties its first sighting to its final
 *   version (A14).
 * @property club the wire club value, e.g. `"7-iron"`.
 * @property carryYards the server's carry, or its conditions-adjusted estimate when
 *   [carryEstimated].
 * @property carryEstimated the carry was adjusted for conditions, so it's an estimate.
 * @property totalYards carry + estimated roll (always an estimate), or `null` when the shot
 *   couldn't be flown.
 * @property offlineYards where it lands relative to the target line, positive right, or `null`
 *   when the shot reported no horizontal launch angle (the miss is then distance-only).
 * @property apexYards the simulated peak height (an estimate), or `null` when it couldn't be
 *   flown.
 */
data class GameShot(
    val eventId: String,
    val club: String,
    val carryYards: Double,
    val carryEstimated: Boolean = false,
    val totalYards: Double? = null,
    val offlineYards: Double? = null,
    val apexYards: Double? = null,
)

/**
 * Where a shot aims.
 *
 * @property distanceYards down the target line.
 * @property lateralYards off the target line, positive right (a pin tucked right, say).
 */
data class GameTarget(
    val distanceYards: Double,
    val lateralYards: Double = 0.0,
)

/**
 * How one shot scored in its mode.
 *
 * @property points the mode's per-shot number: yards off for [GameMode.TargetCallout] and
 *   [GameMode.ClosestToPin] (lower is better), points for the others (higher is better).
 * @property deltaYards the miss: signed (long is positive) for [GameMode.TargetCallout] and the
 *   carry miss of [GameMode.IconicShots]; the straight-line miss for the others; `null` for a
 *   [GameMode.GolfPong] shot with no cups left.
 * @property lateralUnknown the shot had no side measurement, so only its distance counted.
 * @property metricEstimated the number scored was an estimate (total, or a conditions-adjusted
 *   carry), so the UI shows "est.".
 * @property sunkCupIndex the [GameMode.GolfPong] cup this shot sank, if any.
 */
data class ShotScore(
    val points: Double,
    val deltaYards: Double?,
    val lateralUnknown: Boolean = false,
    val metricEstimated: Boolean = false,
    val sunkCupIndex: Int? = null,
)
