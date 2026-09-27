// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import dev.openflight.companion.core.flight.FlightGoldens.apexYards
import dev.openflight.companion.core.flight.FlightGoldens.carryYards
import dev.openflight.companion.core.flight.FlightGoldens.landingAngleDegrees
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Plan F2b: the render model, unconstrained at ISA 1.225, reproduces the TrackMan 2009 tour
 * averages ([TrackManTourAverages], with its source and transcription caveat). The reference
 * renderer's model gave a 13 yd driver apex here (TrackMan: 31) and a 20–25 % long 7-iron.
 */
class TrackManTourGoldenTest {
    private class Flown(
        val row: TrackManTourAverages.Row,
        val trajectory: FlightTrajectory,
    ) {
        val carryError: Double get() = trajectory.carryYards / row.carryYards - 1.0
        val apexError: Double get() = trajectory.apexYards - row.maxHeightYards
    }

    private fun fly(rows: List<TrackManTourAverages.Row>) =
        rows.map { Flown(it, FlightGoldens.unconstrained.simulate(FlightGoldens.input(it))) }

    private fun rms(values: List<Double>) = sqrt(values.sumOf { it * it } / values.size)

    @Test
    fun pgaCarryWithinSixPercentAndApexWithinThreeYards() {
        for (flown in fly(TrackManTourAverages.PGA_2009)) {
            println(
                "PGA ${flown.row.club}: carry ${flown.trajectory.carryYards} vs ${flown.row.carryYards}, " +
                    "apex ${flown.trajectory.apexYards} vs ${flown.row.maxHeightYards}, " +
                    "land ${flown.trajectory.landingAngleDegrees}, T ${flown.trajectory.flightTime}",
            )
            assertThat(abs(flown.carryError), "carry ${flown.row.club}").isLessThan(0.06)
            assertThat(abs(flown.apexError), "apex ${flown.row.club}").isLessThan(3.0)
        }
    }

    @Test
    fun lpgaCarryWithinSixPercentAndApexWithinThreeYards() {
        for (flown in fly(TrackManTourAverages.LPGA_2009)) {
            assertThat(abs(flown.carryError), "carry ${flown.row.club}").isLessThan(0.06)
            assertThat(abs(flown.apexError), "apex ${flown.row.club}").isLessThan(3.0)
        }
    }

    @Test
    fun pgaRmsCarryAndApexErrorsStaySmall() {
        val flown = fly(TrackManTourAverages.PGA_2009)

        assertThat(rms(flown.map { it.carryError })).isLessThan(0.035)
        assertThat(rms(flown.map { it.apexError })).isLessThan(2.0)
    }

    @Test
    fun landingAngleRisesFromDriverToPitchingWedge() {
        val pga = fly(TrackManTourAverages.PGA_2009).associate { it.row.club to it.trajectory.landingAngleDegrees }
        val lpga = fly(TrackManTourAverages.LPGA_2009).associate { it.row.club to it.trajectory.landingAngleDegrees }
        val driver = pga.getValue("driver")
        val fiveIron = pga.getValue("5-iron")
        val sevenIron = pga.getValue("7-iron")
        val wedge = pga.getValue("pw")

        // TrackMan's later table: driver lands at 38°, the wedges at about 50–52°.
        assertThat(driver).isBetween(34.0, 42.0)
        assertThat(wedge).isBetween(44.0, 54.0)
        assertThat(fiveIron).isGreaterThan(driver)
        assertThat(sevenIron).isGreaterThan(fiveIron)
        assertThat(wedge).isGreaterThan(sevenIron)
        assertThat(lpga.getValue("7-iron")).isGreaterThan(lpga.getValue("driver"))
        assertThat(lpga.getValue("pw")).isGreaterThan(lpga.getValue("5-iron"))
    }

    @Test
    fun driverHangTimeIsATourDriversNotALineDrive() {
        val driver = fly(TrackManTourAverages.PGA_2009).first { it.row.club == "driver" }.trajectory

        // The reference renderer flew this drive in about 3.75 s with a 13 yd apex.
        assertThat(driver.flightTime).isBetween(6.3, 7.5)
        assertThat(driver.apexYards).isGreaterThan(28.0)
    }
}
