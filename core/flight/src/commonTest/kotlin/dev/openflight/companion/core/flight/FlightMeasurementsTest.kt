// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FlightMeasurementsTest {
    private val resolver = FlightInputResolver()

    @Test
    fun resolvingMeasurementsMatchesResolvingTheShotEventTheyCameFrom() {
        val shot = makeDrivingRangeShot()

        assertThat(resolver.resolve(shot.toFlightMeasurements())).isEqualTo(resolver.resolve(shot))
    }

    @Test
    fun missingSideMeasurementsAreEstimatedFromTheClubDefaults() {
        val input =
            resolver.resolve(
                FlightMeasurements(id = "pi-row", club = "7-iron", ballSpeedMph = 120.0, carryYards = 165.0),
            )

        assertThat(input.eventId).isEqualTo("pi-row")
        assertThat(input.horizontalLaunchDegrees).isEqualTo(0.0)
        assertThat(input.spinAxisDegrees).isEqualTo(0.0)
        assertThat(input.provenance.estimatedParameters).isEqualTo(FlightParameter.entries.toSet())
    }

    @Test
    fun aNonPositiveCarryIsRejected() {
        assertFailsWith<FlightInputResolutionError.InvalidCarry> {
            resolver.resolve(FlightMeasurements(id = "x", club = "driver", ballSpeedMph = 150.0, carryYards = 0.0))
        }
    }
}
