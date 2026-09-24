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
 * [FlightInputProvenance]. Ported from `FlightInputResolver.swift`; the per-club defaults table
 * is copied verbatim.
 */
class FlightInputResolver {
    private data class ClubFlightDefaults(
        val launchDegrees: Double,
        val spinRpm: Double,
    )

    private data class ResolvedMeasurements(
        val launchDegrees: Double,
        val horizontalDegrees: Double,
        val spinRpm: Double,
        val spinAxisDegrees: Double,
        val provenance: FlightInputProvenance,
    )

    fun resolve(shot: ShotEvent): FlightInput {
        validate(shot)
        val measurements = resolveMeasurements(shot, defaultsForClub(shot.club))

        return FlightInput(
            eventId = shot.eventId,
            ballSpeedMetersPerSecond = shot.ballSpeedMph * MILES_PER_HOUR_TO_METERS_PER_SECOND,
            launchAngleDegrees = measurements.launchDegrees,
            horizontalLaunchDegrees = measurements.horizontalDegrees,
            spinRpm = measurements.spinRpm,
            spinAxisDegrees = measurements.spinAxisDegrees,
            targetCarryMeters = shot.estimatedCarryYards * YARDS_TO_METERS,
            windMetersPerSecond = Vec3.ZERO,
            provenance = measurements.provenance,
        )
    }

    private fun validate(shot: ShotEvent) {
        if (!shot.ballSpeedMph.isFinite() || shot.ballSpeedMph <= 0) {
            throw FlightInputResolutionError.InvalidBallSpeed
        }
        if (!shot.estimatedCarryYards.isFinite() || shot.estimatedCarryYards <= 0) {
            throw FlightInputResolutionError.InvalidCarry
        }
    }

    private fun resolveMeasurements(
        shot: ShotEvent,
        defaults: ClubFlightDefaults,
    ): ResolvedMeasurements {
        val estimated = mutableSetOf<FlightParameter>()
        val clamped = mutableSetOf<FlightParameter>()

        val launch =
            resolved(
                shot.launchAngleVertical,
                fallback = defaults.launchDegrees,
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
        val spin =
            resolved(
                shot.spinRpm,
                fallback = defaults.spinRpm,
                min = SPIN_RATE_MIN_RPM,
                max = SPIN_RATE_MAX_RPM,
                parameter = FlightParameter.SPIN_RATE,
                estimated = estimated,
                clamped = clamped,
            )
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

    @Suppress("CyclomaticComplexMethod")
    private fun defaultsForClub(club: String): ClubFlightDefaults {
        val normalized =
            club
                .lowercase()
                .replace('_', '-')
                .replace(' ', '-')

        return when (normalized) {
            "driver" -> {
                ClubFlightDefaultsTable.DRIVER
            }

            "3-wood", "5-wood", "7-wood" -> {
                ClubFlightDefaultsTable.WOOD
            }

            "3-hybrid", "5-hybrid", "7-hybrid", "9-hybrid" -> {
                ClubFlightDefaultsTable.HYBRID
            }

            "2-iron", "3-iron", "4-iron", "iron-2", "iron-3", "iron-4" -> {
                ClubFlightDefaultsTable.LONG_IRON
            }

            "5-iron", "6-iron", "7-iron", "iron-5", "iron-6", "iron-7" -> {
                ClubFlightDefaultsTable.MID_IRON
            }

            "8-iron", "9-iron", "iron-8", "iron-9" -> {
                ClubFlightDefaultsTable.SHORT_IRON
            }

            "pw", "gw", "sw", "lw", "pitching-wedge", "gap-wedge", "sand-wedge", "lob-wedge" -> {
                ClubFlightDefaultsTable.WEDGE
            }

            else -> {
                ClubFlightDefaultsTable.UNKNOWN
            }
        }
    }

    /** The per-club launch/spin defaults table from `FlightInputResolver.swift`, verbatim. */
    private object ClubFlightDefaultsTable {
        val DRIVER = ClubFlightDefaults(launchDegrees = 12.0, spinRpm = 2_500.0)
        val WOOD = ClubFlightDefaults(launchDegrees = 15.0, spinRpm = 3_500.0)
        val HYBRID = ClubFlightDefaults(launchDegrees = 18.0, spinRpm = 4_200.0)
        val LONG_IRON = ClubFlightDefaults(launchDegrees = 17.0, spinRpm = 4_500.0)
        val MID_IRON = ClubFlightDefaults(launchDegrees = 21.0, spinRpm = 5_500.0)
        val SHORT_IRON = ClubFlightDefaults(launchDegrees = 26.0, spinRpm = 7_000.0)
        val WEDGE = ClubFlightDefaults(launchDegrees = 31.0, spinRpm = 8_500.0)
        val UNKNOWN = ClubFlightDefaults(launchDegrees = 18.0, spinRpm = 4_500.0)
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
    }
}
