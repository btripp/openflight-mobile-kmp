// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.ShotEvent

/**
 * Why [FlightInputResolver.resolve] could not build a [FlightInput], ported from
 * `ios/OpenFlight/DrivingRange/FlightInputResolver.swift`'s `FlightInputResolutionError`.
 */
sealed class FlightInputResolutionError(
    message: String,
) : Exception(message) {
    object InvalidBallSpeed : FlightInputResolutionError("Ball speed is unavailable for this shot.")

    object InvalidCarry : FlightInputResolutionError("Carry distance is unavailable for this shot.")
}

/**
 * Turns a raw [ShotEvent] into a physically-sane [FlightInput], filling missing or non-finite
 * measurements from a per-club defaults table and clamping out-of-range ones, recording both as
 * [FlightInputProvenance]. Ported from `FlightInputResolver.swift`.
 *
 * Plan F2b:
 * - The per-club defaults are the backend's `CLUB_PHYSICS` ([ClubPhysics]), so an estimated flight
 *   is the one the server's carry assumes.
 * - A spin the server calculated (`spin_source = "calculated"`, from `170·v·sin(LA)^1.2`) or
 *   substituted (`"club_typical"`) is flown but recorded as estimated. For drivers and woods a
 *   calculated spin is replaced by the club's typical spin, because the kinematic formula
 *   overstates it there (about 3850 rpm at 167 mph and 10.9°, against a tour average of 2686).
 * - A missing spin axis flies as 0, so the ball keeps its start line and doesn't curve; it's
 *   recorded as estimated.
 */
class FlightInputResolver {
    private data class ResolvedMeasurements(
        val launchDegrees: Double,
        val horizontalDegrees: Double,
        val spinRpm: Double,
        val spinAxisDegrees: Double,
        val provenance: FlightInputProvenance,
    )

    fun resolve(shot: ShotEvent): FlightInput = resolve(shot.toFlightMeasurements())

    /**
     * Same as [resolve] for a [ShotEvent], for sources that aren't one (for example a Pi session
     * row). [FlightMeasurements.id] becomes [FlightInput.eventId].
     */
    fun resolve(shot: FlightMeasurements): FlightInput {
        validate(shot)
        val measurements = resolveMeasurements(shot, ClubPhysics.forClub(shot.club))

        return FlightInput(
            eventId = shot.id,
            ballSpeedMetersPerSecond = shot.ballSpeedMph * MILES_PER_HOUR_TO_METERS_PER_SECOND,
            launchAngleDegrees = measurements.launchDegrees,
            horizontalLaunchDegrees = measurements.horizontalDegrees,
            spinRpm = measurements.spinRpm,
            spinAxisDegrees = measurements.spinAxisDegrees,
            targetCarryMeters = shot.carryYards * YARDS_TO_METERS,
            windMetersPerSecond = Vec3.ZERO,
            provenance = measurements.provenance,
        )
    }

    private fun validate(shot: FlightMeasurements) {
        if (!shot.ballSpeedMph.isFinite() || shot.ballSpeedMph <= 0) {
            throw FlightInputResolutionError.InvalidBallSpeed
        }
        if (!shot.carryYards.isFinite() || shot.carryYards <= 0) {
            throw FlightInputResolutionError.InvalidCarry
        }
    }

    private fun resolveMeasurements(
        shot: FlightMeasurements,
        physics: ClubPhysics,
    ): ResolvedMeasurements {
        val estimated = mutableSetOf<FlightParameter>()
        val clamped = mutableSetOf<FlightParameter>()

        val launch =
            resolved(
                shot.launchAngleVertical,
                fallback = physics.defaultLaunchDegrees(shot.ballSpeedMph),
                min = LAUNCH_ANGLE_MIN_DEGREES,
                max = LAUNCH_ANGLE_MAX_DEGREES,
                parameter = FlightParameter.LAUNCH_ANGLE,
                estimated = estimated,
                clamped = clamped,
            )
        val horizontal =
            resolved(
                shot.launchAngleHorizontal,
                fallback = HORIZONTAL_LAUNCH_FALLBACK_DEGREES,
                min = HORIZONTAL_LAUNCH_MIN_DEGREES,
                max = HORIZONTAL_LAUNCH_MAX_DEGREES,
                parameter = FlightParameter.HORIZONTAL_LAUNCH,
                estimated = estimated,
                clamped = clamped,
            )
        val calculatedWoodSpin = shot.spinSource == SPIN_SOURCE_CALCULATED && physics.isDriverOrWood
        val spin =
            resolved(
                if (calculatedWoodSpin) null else shot.spinRpm,
                fallback = physics.typicalSpinRpm,
                min = SPIN_RATE_MIN_RPM,
                max = SPIN_RATE_MAX_RPM,
                parameter = FlightParameter.SPIN_RATE,
                estimated = estimated,
                clamped = clamped,
            )
        if (shot.spinSource in ESTIMATED_SPIN_SOURCES) estimated.add(FlightParameter.SPIN_RATE)
        val spinAxis =
            resolved(
                shot.spinAxisDeg,
                fallback = SPIN_AXIS_FALLBACK_DEGREES,
                min = SPIN_AXIS_MIN_DEGREES,
                max = SPIN_AXIS_MAX_DEGREES,
                parameter = FlightParameter.SPIN_AXIS,
                estimated = estimated,
                clamped = clamped,
            )

        return ResolvedMeasurements(
            launchDegrees = launch,
            horizontalDegrees = horizontal,
            spinRpm = spin,
            spinAxisDegrees = spinAxis,
            provenance = FlightInputProvenance(estimatedParameters = estimated, clampedParameters = clamped),
        )
    }

    @Suppress("LongParameterList")
    private fun resolved(
        value: Double?,
        fallback: Double,
        min: Double,
        max: Double,
        parameter: FlightParameter,
        estimated: MutableSet<FlightParameter>,
        clamped: MutableSet<FlightParameter>,
    ): Double {
        if (value == null || !value.isFinite()) {
            estimated.add(parameter)
            return fallback
        }
        val bounded = value.coerceIn(min, max)
        if (bounded != value) {
            clamped.add(parameter)
        }
        return bounded
    }

    private companion object {
        const val MILES_PER_HOUR_TO_METERS_PER_SECOND = 0.44704
        const val YARDS_TO_METERS = 0.9144

        const val LAUNCH_ANGLE_MIN_DEGREES = 1.0
        const val LAUNCH_ANGLE_MAX_DEGREES = 55.0
        const val HORIZONTAL_LAUNCH_MIN_DEGREES = -45.0
        const val HORIZONTAL_LAUNCH_MAX_DEGREES = 45.0
        const val HORIZONTAL_LAUNCH_FALLBACK_DEGREES = 0.0
        const val SPIN_RATE_MIN_RPM = 0.0
        const val SPIN_RATE_MAX_RPM = 12_000.0
        const val SPIN_AXIS_MIN_DEGREES = -60.0
        const val SPIN_AXIS_MAX_DEGREES = 60.0
        const val SPIN_AXIS_FALLBACK_DEGREES = 0.0

        const val SPIN_SOURCE_CALCULATED = "calculated"
        val ESTIMATED_SPIN_SOURCES = setOf(SPIN_SOURCE_CALCULATED, "club_typical")
    }
}
