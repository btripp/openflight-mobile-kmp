// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

/**
 * Representative launches for the conditions tests: the backend's own per-club defaults
 * (`clubs/physics.py` `CLUB_PHYSICS` at 7ca4b40: amateur ball speed, baseline launch and the
 * TrackMan PGA Tour typical spin), the same launches `BackendDensityOracle` uses.
 */
internal object ConditionsLaunches {
    val DRIVER =
        measured(club = "driver", speedMph = 143.0, launch = 11.0, spin = 2_700.0, carry = 225.0)
    val SEVEN_IRON =
        measured(club = "7-iron", speedMph = 100.0, launch = 20.5, spin = 6_500.0, carry = 133.0)
    val PITCHING_WEDGE =
        measured(club = "pw", speedMph = 82.0, launch = 28.0, spin = 9_000.0, carry = 101.0)

    fun measured(
        club: String,
        speedMph: Double,
        launch: Double,
        spin: Double,
        carry: Double,
    ): FlightMeasurements =
        FlightMeasurements(
            id = club,
            club = club,
            ballSpeedMph = speedMph,
            carryYards = carry,
            launchAngleVertical = launch,
            launchAngleHorizontal = 0.0,
            spinRpm = spin,
            spinAxisDeg = 0.0,
        )

    fun input(measurements: FlightMeasurements): FlightInput = FlightInputResolver().resolve(measurements)
}
