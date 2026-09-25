// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ShotDetail

/**
 * The launch measurements [FlightInputResolver] needs, independent of where the shot came from.
 * Field names and units follow [ShotEvent]; `null` means "not measured" and is filled from the
 * per-club defaults (and recorded in [FlightInputProvenance.estimatedParameters]).
 *
 * [carryYards] is the server's table carry (`estimated_carry_yards`); [carrySpinAdjustedYards]
 * is the server's ballistic carry (`carry_spin_adjusted`, Socket.IO/history rows only), which
 * [ShotDistanceEstimator] prefers as its anchor when present.
 */
data class FlightMeasurements(
    val id: String,
    val club: String,
    val ballSpeedMph: Double,
    val carryYards: Double,
    val launchAngleVertical: Double? = null,
    val launchAngleHorizontal: Double? = null,
    val spinRpm: Double? = null,
    val spinAxisDeg: Double? = null,
    val carrySpinAdjustedYards: Double? = null,
)

/** This shot's measurements, keyed by its `event_id`. */
fun ShotEvent.toFlightMeasurements(): FlightMeasurements =
    FlightMeasurements(
        id = eventId,
        club = club,
        ballSpeedMph = ballSpeedMph,
        carryYards = estimatedCarryYards,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        spinRpm = spinRpm,
        spinAxisDeg = spinAxisDeg,
    )

/**
 * A Pi session/history row's measurements, keyed by its `timestamp` (the join key with the live
 * [ShotEvent]), including the server's spin-adjusted carry. `null` for a swing-speed rep or a row
 * without ball speed or carry.
 */
fun ShotDetail.toFlightMeasurements(): FlightMeasurements? {
    val ballSpeed = ballSpeedMph
    val carry = estimatedCarryYards
    if (isSwingSpeed || ballSpeed == null || carry == null) return null
    return FlightMeasurements(
        id = timestamp,
        club = club.orEmpty(),
        ballSpeedMph = ballSpeed,
        carryYards = carry,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        spinRpm = spinRpm,
        spinAxisDeg = spinAxisDeg,
        carrySpinAdjustedYards = carrySpinAdjusted,
    )
}
