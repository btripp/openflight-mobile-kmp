// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Made-up shots for Demo mode (plan F14): realistic and varied for the club, with no launch monitor
 * anywhere. They are **not** measurements, and the app labels them "Demo" wherever they show.
 *
 * Each shot starts from the club's priors in [ClubPhysics] (the backend's `CLUB_PHYSICS`: the
 * amateur average ball speed, the speed-adjusted baseline launch and the tour spin average) and
 * adds natural scatter drawn from a seeded [Random], so a [seed] always yields the same sequence:
 * - ball speed: ±[BALL_SPEED_SPREAD] (one standard deviation), with an occasional thin strike
 *   ([MISHIT_CHANCE]) that loses [MISHIT_SPEED_LOSS_MIN]–[MISHIT_SPEED_LOSS_MAX] of its speed and
 *   gains spin;
 * - launch: [ClubPhysics.defaultLaunchDegrees] at that speed, ±[LAUNCH_SPREAD_DEGREES];
 * - spin: the club's typical spin, ±[SPIN_SPREAD];
 * - start line ±[HORIZONTAL_SPREAD_DEGREES] and spin axis ±[AXIS_SPREAD_DEGREES], each clamped, and
 *   a club path that keeps the server's `spin_axis = HLA − path` relation (`server.py:3051`);
 * - a club speed from a per-club smash factor.
 *
 * The carry is not drawn from a table: it is the flight of exactly those numbers through the F2b
 * model ([BallFlightSimulator.Configuration.conditions]: Ferguson aerodynamics, 0.04/s spin decay,
 * ISA air, unconstrained), so the range draws the shot landing where its carry says.
 */
class DemoShotGenerator(
    seed: Long,
    private val simulator: BallFlightSimulator = BallFlightSimulator(BallFlightSimulator.Configuration.conditions),
) {
    private val random = Random(seed)

    /** The next shot for [club] (a `GolfClub` wire value, e.g. `"7-iron"`). */
    fun next(club: String): DemoShot {
        val physics = ClubPhysics.forClub(club)
        val mishit = random.nextDouble() < MISHIT_CHANCE
        val speedLoss = if (mishit) random.nextDouble(MISHIT_SPEED_LOSS_MIN, MISHIT_SPEED_LOSS_MAX) else 0.0
        val ballSpeed =
            (physics.averageBallSpeedMph * (1 + gaussian() * BALL_SPEED_SPREAD) * (1 - speedLoss))
                .coerceAtLeast(MINIMUM_BALL_SPEED_MPH)
        val launch =
            (physics.defaultLaunchDegrees(ballSpeed) + gaussian() * LAUNCH_SPREAD_DEGREES)
                .coerceIn(MINIMUM_LAUNCH_DEGREES, MAXIMUM_LAUNCH_DEGREES)
        val spinFactor = (1 + gaussian() * SPIN_SPREAD) * (if (mishit) MISHIT_SPIN_GAIN else 1.0)
        val spin = (physics.typicalSpinRpm * spinFactor).coerceIn(MINIMUM_SPIN_RPM, MAXIMUM_SPIN_RPM)
        val horizontal = (gaussian() * HORIZONTAL_SPREAD_DEGREES).coerceIn(-MAXIMUM_HORIZONTAL, MAXIMUM_HORIZONTAL)
        val axis = (gaussian() * AXIS_SPREAD_DEGREES).coerceIn(-MAXIMUM_AXIS, MAXIMUM_AXIS)
        val smash = (smashFactorFor(club) + gaussian() * SMASH_SPREAD).coerceIn(MINIMUM_SMASH, MAXIMUM_SMASH)
        val carryMeters =
            simulator
                .simulate(
                    FlightInput(
                        eventId = DEMO_EVENT_ID,
                        ballSpeedMetersPerSecond = ballSpeed * MPH_TO_METERS_PER_SECOND,
                        launchAngleDegrees = launch,
                        horizontalLaunchDegrees = horizontal,
                        spinRpm = spin,
                        spinAxisDegrees = axis,
                        targetCarryMeters = 0.0,
                        provenance = FlightInputProvenance(),
                    ),
                ).carryMeters
        return DemoShot(
            club = club,
            ballSpeedMph = tenths(ballSpeed),
            clubSpeedMph = tenths(ballSpeed / smash),
            smashFactor = hundredths(smash),
            launchAngleVertical = tenths(launch),
            launchAngleHorizontal = tenths(horizontal),
            spinRpm = round(spin),
            spinAxisDeg = tenths(axis),
            clubPathDeg = tenths(horizontal - axis),
            carryYards = tenths(carryMeters / YARDS_TO_METERS),
        )
    }

    /** A standard normal draw (Box–Muller). */
    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = random.nextDouble()
        return sqrt(BOX_MULLER_SCALE * ln(u1)) * cos(2 * PI * u2)
    }

    companion object {
        /** One standard deviation of ball speed, as a fraction of the club's average. */
        const val BALL_SPEED_SPREAD = 0.025
        const val LAUNCH_SPREAD_DEGREES = 1.2
        const val SPIN_SPREAD = 0.08
        const val HORIZONTAL_SPREAD_DEGREES = 2.2
        const val AXIS_SPREAD_DEGREES = 4.5
        const val SMASH_SPREAD = 0.012

        /** How often a strike comes off thin: slower, with more spin. */
        const val MISHIT_CHANCE = 0.12
        const val MISHIT_SPEED_LOSS_MIN = 0.05
        const val MISHIT_SPEED_LOSS_MAX = 0.12
        private const val MISHIT_SPIN_GAIN = 1.12

        private const val MINIMUM_BALL_SPEED_MPH = 30.0
        private const val MINIMUM_LAUNCH_DEGREES = 4.0
        private const val MAXIMUM_LAUNCH_DEGREES = 45.0
        private const val MINIMUM_SPIN_RPM = 1_200.0
        private const val MAXIMUM_SPIN_RPM = 11_500.0
        private const val MAXIMUM_HORIZONTAL = 7.0
        private const val MAXIMUM_AXIS = 15.0
        private const val MINIMUM_SMASH = 1.0
        private const val MAXIMUM_SMASH = 1.5
        private const val DEMO_EVENT_ID = "demo"
        private const val BOX_MULLER_SCALE = -2.0
        private const val TENTHS = 10.0
        private const val HUNDREDTHS = 100.0
        private const val MPH_TO_METERS_PER_SECOND = 0.44704
        private const val YARDS_TO_METERS = 0.9144

        /**
         * Typical amateur smash factors (ball speed ÷ club speed), falling from the driver to the
         * lob wedge as loft rises. Illustrative, like the rest of a demo shot.
         */
        @Suppress("MagicNumber")
        private val SMASH_BY_CLUB: Map<String, Double> =
            mapOf(
                "driver" to 1.45,
                "3-wood" to 1.43,
                "5-wood" to 1.41,
                "7-wood" to 1.39,
                "3-hybrid" to 1.38,
                "5-hybrid" to 1.37,
                "7-hybrid" to 1.35,
                "9-hybrid" to 1.33,
                "2-iron" to 1.36,
                "3-iron" to 1.35,
                "4-iron" to 1.34,
                "5-iron" to 1.33,
                "6-iron" to 1.32,
                "7-iron" to 1.31,
                "8-iron" to 1.29,
                "9-iron" to 1.27,
                "pw" to 1.24,
                "gw" to 1.22,
                "sw" to 1.18,
                "lw" to 1.15,
            )
        private const val DEFAULT_SMASH = 1.3

        private fun smashFactorFor(club: String): Double = SMASH_BY_CLUB[club.lowercase()] ?: DEFAULT_SMASH

        private fun tenths(value: Double): Double = round(value * TENTHS) / TENTHS

        private fun hundredths(value: Double): Double = round(value * HUNDREDTHS) / HUNDREDTHS
    }
}

/**
 * One made-up Demo mode shot ([DemoShotGenerator]), in the wire's units: mph, degrees, rpm and
 * yards. [carryYards] is the simulated flight's carry of exactly these numbers.
 */
data class DemoShot(
    val club: String,
    val ballSpeedMph: Double,
    val clubSpeedMph: Double,
    val smashFactor: Double,
    val launchAngleVertical: Double,
    val launchAngleHorizontal: Double,
    val spinRpm: Double,
    val spinAxisDeg: Double,
    val clubPathDeg: Double,
    val carryYards: Double,
)
