// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.ShotEvent

/**
 * The launch measurements [FlightInputResolver] needs, independent of where the shot came from.
 * Field names and units follow [ShotEvent]; `null` means "not measured" and is filled from the
 * per-club defaults (and recorded in [FlightInputProvenance.estimatedParameters]).
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
