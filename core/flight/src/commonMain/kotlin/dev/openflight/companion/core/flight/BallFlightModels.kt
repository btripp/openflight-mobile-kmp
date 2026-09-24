// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

/**
 * The flight parameters that can come from a measured [dev.openflight.companion.core.model.ShotEvent]
 * or fall back to a per-club default, ported from `ios/OpenFlight/DrivingRange/BallFlightModels.swift`.
 */
enum class FlightParameter {
    LAUNCH_ANGLE,
    HORIZONTAL_LAUNCH,
    SPIN_RATE,
    SPIN_AXIS,
}

/**
 * Which [FlightParameter]s were estimated (missing/non-finite, filled from the per-club default
 * table) or clamped (measured but out of the physically sane range) when resolving a
 * [FlightInput]. Ported from `BallFlightModels.swift`'s `FlightInputProvenance`.
 */
data class FlightInputProvenance(
    val estimatedParameters: Set<FlightParameter> = emptySet(),
    val clampedParameters: Set<FlightParameter> = emptySet(),
) {
    /** True when the flight leans on estimated launch angle or spin rate, not measured values. */
    val usesEstimatedFlight: Boolean
        get() =
            FlightParameter.LAUNCH_ANGLE in estimatedParameters ||
                FlightParameter.SPIN_RATE in estimatedParameters
}

/**
 * The resolved, physically-sane inputs to [BallFlightSimulator.simulate], ported from
 * `BallFlightModels.swift`'s `FlightInput`. `eventId` is a `String` (matching
 * [dev.openflight.companion.core.model.ShotEvent.eventId]) instead of a UUID type.
 */
data class FlightInput(
    val eventId: String,
    val ballSpeedMetersPerSecond: Double,
    val launchAngleDegrees: Double,
    val horizontalLaunchDegrees: Double,
    val spinRpm: Double,
    val spinAxisDegrees: Double,
    val targetCarryMeters: Double,
    val windMetersPerSecond: Vec3 = Vec3.ZERO,
    val provenance: FlightInputProvenance,
)

/** One instant of the simulated flight. Ported from `BallFlightModels.swift`'s `FlightPoint`. */
data class FlightPoint(
    val time: Double,
    val positionMeters: Vec3,
    val velocityMetersPerSecond: Vec3,
)

/**
 * A full simulated ball flight: a resampled list of [FlightPoint]s plus the derived summary
 * values the driving-range UI reads. Ported from `BallFlightModels.swift`'s `FlightTrajectory`.
 *
 * `id` defaults to [eventId] rather than a fresh random UUID (the Swift default is `UUID()`,
 * used only for SwiftUI `Identifiable` list diffing); nothing in the pure math depends on `id`
 * being distinct from `eventId`, and this avoids taking a random-UUID dependency in a pure-math
 * module.
 */
data class FlightTrajectory(
    val eventId: String,
    val points: List<FlightPoint>,
    val provenance: FlightInputProvenance,
    val id: String = eventId,
) {
    val apexMeters: Double = points.maxOfOrNull { it.positionMeters.y } ?: 0.0
    val flightTime: Double = points.lastOrNull()?.time ?: 0.0
    val carryMeters: Double = points.lastOrNull()?.positionMeters?.z ?: 0.0
    val lateralMeters: Double = points.lastOrNull()?.positionMeters?.x ?: 0.0

    /** `clamp(flightTime * 0.68, 3.5, 6)` seconds, matching the reference exactly. */
    val playbackDuration: Double
        get() = (flightTime * PLAYBACK_DURATION_SCALE).coerceIn(MINIMUM_PLAYBACK_SECONDS, MAXIMUM_PLAYBACK_SECONDS)

    /**
     * The interpolated point at [time], or `null` when there are no points. Clamps to the first
     * or last point outside the trajectory's time range, and otherwise binary-searches the
     * bracketing frame pair and linearly interpolates position and velocity. Ported from
     * `FlightTrajectory.point(at:)`.
     */
    fun point(at: Double): FlightPoint? {
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        return when {
            first == null || last == null -> null
            at <= first.time -> first
            at >= last.time -> last
            else -> interpolate(at)
        }
    }

    /** Binary-searches the bracketing frame pair and linearly interpolates position and velocity. */
    private fun interpolate(at: Double): FlightPoint {
        var lower = 0
        var upper = points.size - 1
        while (upper - lower > 1) {
            val middle = (lower + upper) / 2
            if (points[middle].time <= at) {
                lower = middle
            } else {
                upper = middle
            }
        }

        val start = points[lower]
        val end = points[upper]
        val interval = end.time - start.time
        return if (interval <= 0) {
            start
        } else {
            val progress = (at - start.time) / interval
            FlightPoint(
                time = at,
                positionMeters = start.positionMeters + (end.positionMeters - start.positionMeters) * progress,
                velocityMetersPerSecond =
                    start.velocityMetersPerSecond +
                        (end.velocityMetersPerSecond - start.velocityMetersPerSecond) * progress,
            )
        }
    }

    private companion object {
        const val PLAYBACK_DURATION_SCALE = 0.68
        const val MINIMUM_PLAYBACK_SECONDS = 3.5
        const val MAXIMUM_PLAYBACK_SECONDS = 6.0
    }
}
