// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.Firmness
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a distance came from. Estimated values are labelled as such end to end (plan §0.2). */
enum class DistanceProvenance {
    /** The server's number, unchanged. */
    SERVER,

    /** Computed by the app (conditions-adjusted carry, roll, total). */
    ESTIMATED,
}

/** The estimated run after carry, in yards, never negative. Always [DistanceProvenance.ESTIMATED]. */
data class RollEstimate(
    val rollYards: Double,
    val provenance: DistanceProvenance = DistanceProvenance.ESTIMATED,
)

/**
 * Estimates the run (bounces plus roll) after the ball lands, from the landing velocity and
 * backspin of the **unscaled** conditions run.
 *
 * **Model.** Each bounce is the rigid-sphere bounce with Coulomb friction and restitution
 * (Daish, *The Physics of Ball Games*, 1981), evaluated against a surface tilted up by an
 * effective angle β toward the incoming ball, Penner's device for turf deforming under the ball
 * (A. R. Penner, "The run of a golf ball", Can. J. Phys. 80:931–940, 2002). In the tilted frame,
 * with tangential speed `u`, normal speed `v_n < 0` and backspin surface speed `s = ωR`:
 * - `(u + s)/|v_n| > 7/2·μ(1+r)`: slides forward throughout, `u' = u + μ(1+r)v_n`,
 *   `s' = s + 5/2·μ(1+r)v_n`;
 * - `(u + s)/|v_n| < −7/2·μ(1+r)`: slides backward, `u' = u − μ(1+r)v_n`, `s' = s − 5/2·μ(1+r)v_n`;
 * - otherwise it grips and rolls: `u' = 5/7·u − 2/7·s`, `s' = −5/7·u + 2/7·s`;
 * - and always `v_n' = −r·v_n`.
 *
 * These are equations (5) of Biber, Jones, Champneys, Green & Szalai, "Measurements and
 * linearized models for golf ball bounce" (University of Bristol / R&A, arXiv:2302.02758, 2023).
 * r, μ and β come from their Table 3 "fixed β" fits to over 1000 measured bounces:
 * - [Firmness.NORMAL]: natural turf (a well-maintained teeing area, Campaign B):
 *   β = 18.4°, r = 0.147, μ = 0.998.
 * - [Firmness.FIRM]: premium artificial turf on a rigid board (Campaign A):
 *   β = 12.9°, r = 0.420, μ = 0.852.
 * - [Firmness.SOFT]: **no published soft-turf fit was found.** It is the NORMAL run × 0.5, a
 *   labelled heuristic factor (like the backend's firmness-free `30·cos(landing°)` heuristic,
 *   `ballistics.py:120-127`, it has no measurement behind it).
 *
 * Bounces repeat, each hop flying ballistically (drag neglected over the short, slow hop), until
 * the rebound's vertical speed is under [STOP_BOUNCING_MPS] (a hop under 5 mm). The remaining
 * forward speed then rolls out against a rolling friction of 0.131, the green value reported by
 * Roh & Lee ("Golf ball landing, bounce and roll on turf", Procedia Engineering 2:3237–3242,
 * 2010). Fairways are slower than greens, so this tail is an upper bound; it is small because the
 * tilted bounce keeps converting forward speed into hops until little is left.
 *
 * Backspin that pulls the ball back is reported as 0 roll, never negative.
 *
 * Cross-check: Biber et al.'s "typical driver tee shot" landing (28.5 m/s, 37.3°, 2094 rpm)
 * gives about 25 yd on NORMAL, close to the backend heuristic's 23.9 yd for that angle; steeper,
 * higher-spinning iron and wedge landings give a few yards or less.
 */
class RollEstimator {
    private data class Turf(
        val effectiveSlopeDegrees: Double,
        val restitution: Double,
        val friction: Double,
    )

    /**
     * The run after [landing] (a simulator point: y up, z downrange, x lateral) with
     * [spinRpmAtLanding] of backspin on a [firmness] surface.
     */
    fun estimate(
        landing: FlightPoint,
        spinRpmAtLanding: Double,
        firmness: Firmness,
    ): RollEstimate {
        val velocity = landing.velocityMetersPerSecond
        val horizontal = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
        val descending = minOf(velocity.y, 0.0)
        val backspin = maxOf(spinRpmAtLanding, 0.0) * RPM_TO_RADIANS_PER_SECOND * BALL_RADIUS_METERS
        val runMeters =
            when (firmness) {
                Firmness.SOFT -> run(horizontal, descending, backspin, NATURAL_TURF) * SOFT_FACTOR
                Firmness.NORMAL -> run(horizontal, descending, backspin, NATURAL_TURF)
                Firmness.FIRM -> run(horizontal, descending, backspin, ARTIFICIAL_TURF)
            }
        return RollEstimate(rollYards = maxOf(runMeters, 0.0) * METERS_TO_YARDS)
    }

    private fun run(
        horizontalSpeed: Double,
        verticalSpeed: Double,
        backspinSurfaceSpeed: Double,
        turf: Turf,
    ): Double {
        var forward = horizontalSpeed
        var vertical = verticalSpeed
        var spin = backspinSurfaceSpeed
        var distance = 0.0
        var bounces = 0
        // Each pass is one bounce; a rebound too slow to hop ends the bouncing (vertical = 0).
        while (vertical < 0.0 && bounces < MAXIMUM_BOUNCES) {
            val rebound = bounce(forward, vertical, spin, turf)
            forward = rebound.forward
            spin = rebound.spin
            bounces += 1
            val hops = rebound.vertical >= STOP_BOUNCING_MPS
            if (hops) distance += forward * 2.0 * rebound.vertical / GRAVITY
            vertical = if (hops) -rebound.vertical else 0.0
        }
        val rolling = maxOf(forward, 0.0)
        return distance + rolling * rolling / (2.0 * ROLLING_FRICTION * GRAVITY)
    }

    private data class Rebound(
        val forward: Double,
        val vertical: Double,
        val spin: Double,
    )

    /** Biber et al. 2023 eq. (5) in Penner's tilted frame; see the class KDoc. */
    private fun bounce(
        forward: Double,
        vertical: Double,
        spin: Double,
        turf: Turf,
    ): Rebound {
        val slope = turf.effectiveSlopeDegrees * DEGREES_TO_RADIANS
        val cosine = cos(slope)
        val sine = sin(slope)
        val tangential = forward * cosine + vertical * sine
        val normal = -forward * sine + vertical * cosine
        val impulse = turf.friction * (1.0 + turf.restitution)
        val slipRatio = (tangential + spin) / -normal
        val rollThreshold = GRIP_FACTOR * impulse

        val (tangentialOut, spinOut) =
            when {
                slipRatio > rollThreshold -> {
                    (tangential + impulse * normal) to (spin + SPIN_SLIP_FACTOR * impulse * normal)
                }

                slipRatio < -rollThreshold -> {
                    (tangential - impulse * normal) to (spin - SPIN_SLIP_FACTOR * impulse * normal)
                }

                else -> {
                    (ROLL_KEEP * tangential - ROLL_TRANSFER * spin) to (-ROLL_KEEP * tangential + ROLL_TRANSFER * spin)
                }
            }
        val normalOut = -turf.restitution * normal
        return Rebound(
            forward = tangentialOut * cosine - normalOut * sine,
            vertical = tangentialOut * sine + normalOut * cosine,
            spin = spinOut,
        )
    }

    private companion object {
        // Biber et al. 2023, Table 3, fixed-β columns.
        val NATURAL_TURF = Turf(effectiveSlopeDegrees = 18.4, restitution = 0.147, friction = 0.998)
        val ARTIFICIAL_TURF = Turf(effectiveSlopeDegrees = 12.9, restitution = 0.420, friction = 0.852)

        /** Unsourced heuristic; see the class KDoc. */
        const val SOFT_FACTOR = 0.5

        /** Roh & Lee 2010, green rolling friction. */
        const val ROLLING_FRICTION = 0.131

        // Rigid-sphere constants from eq. (5): 7/2, 5/2, 5/7 and 2/7 (solid-sphere inertia 2/5·mR²).
        const val GRIP_FACTOR = 3.5
        const val SPIN_SLIP_FACTOR = 2.5
        const val ROLL_KEEP = 5.0 / 7.0
        const val ROLL_TRANSFER = 2.0 / 7.0

        /** Numerical stop: a rebound this slow hops under 5 mm. */
        const val STOP_BOUNCING_MPS = 0.3
        const val MAXIMUM_BOUNCES = 30

        const val GRAVITY = 9.81
        const val BALL_RADIUS_METERS = 0.02135
        const val RPM_TO_RADIANS_PER_SECOND = 2.0 * PI / 60.0
        const val DEGREES_TO_RADIANS = PI / 180.0
        const val METERS_TO_YARDS = 1.0 / 0.9144
    }
}
