// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.atan2
import kotlin.math.hypot

/** Shared helpers for the plan F2b golden tests. */
internal object FlightGoldens {
    const val METERS_PER_YARD = 0.9144
    private const val METERS_PER_SECOND_PER_MPH = 0.44704
    private const val DEGREES_PER_RADIAN = 180.0 / kotlin.math.PI

    /** The render model, unconstrained, at ISA 1.225. */
    val unconstrained =
        BallFlightSimulator(
            BallFlightSimulator.Configuration.render.copy(
                carryConstraint = BallFlightSimulator.CarryConstraint.NONE,
            ),
        )

    /** The render model as drawn (drag fit to [FlightInput.targetCarryMeters]). */
    val render = BallFlightSimulator()

    @Suppress("LongParameterList")
    fun input(
        ballSpeedMph: Double,
        launchDegrees: Double,
        spinRpm: Double,
        spinAxisDegrees: Double = 0.0,
        horizontalDegrees: Double = 0.0,
        targetCarryYards: Double = 0.0,
    ): FlightInput =
        FlightInput(
            eventId = "golden",
            ballSpeedMetersPerSecond = ballSpeedMph * METERS_PER_SECOND_PER_MPH,
            launchAngleDegrees = launchDegrees,
            horizontalLaunchDegrees = horizontalDegrees,
            spinRpm = spinRpm,
            spinAxisDegrees = spinAxisDegrees,
            targetCarryMeters = targetCarryYards * METERS_PER_YARD,
            provenance = FlightInputProvenance(),
        )

    fun input(row: TrackManTourAverages.Row): FlightInput =
        input(row.ballSpeedMph, row.launchDegrees, row.spinRpm, targetCarryYards = row.carryYards)

    val FlightTrajectory.carryYards: Double get() = carryMeters / METERS_PER_YARD
    val FlightTrajectory.apexYards: Double get() = apexMeters / METERS_PER_YARD
    val FlightTrajectory.lateralYards: Double get() = lateralMeters / METERS_PER_YARD

    /** The descent angle at landing, degrees below horizontal. */
    val FlightTrajectory.landingAngleDegrees: Double
        get() {
            val velocity = points.last().velocityMetersPerSecond
            return atan2(-velocity.y, hypot(velocity.x, velocity.z)) * DEGREES_PER_RADIAN
        }

    /** The launch elevation of the first sample's velocity, degrees. */
    val FlightTrajectory.launchAngleDegrees: Double
        get() {
            val velocity = points.first().velocityMetersPerSecond
            return atan2(velocity.y, hypot(velocity.x, velocity.z)) * DEGREES_PER_RADIAN
        }
}
