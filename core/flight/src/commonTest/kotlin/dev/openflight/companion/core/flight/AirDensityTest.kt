// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import dev.openflight.companion.core.model.Conditions
import kotlin.test.Test

/** Plan F2 §0.2 air-density checks (dry air unless stated). */
class AirDensityTest {
    @Test
    fun isaSeaLevelIsTheStandardDensity() {
        assertThat(AirDensity.of(Conditions.ISA)).isCloseTo(1.225, 0.002)
    }

    @Test
    fun mileHighAtFifteenDegreesIsAbout1009() {
        val denver = Conditions.ISA.copy(altitudeMeters = 1609.0)

        assertThat(AirDensity.pressureAtAltitudePa(1609.0) / 100.0).isCloseTo(834.0, 1.0)
        assertThat(AirDensity.of(denver)).isCloseTo(1.009, 0.01)
    }

    @Test
    fun mileHighAtTheIsaTemperatureIsAbout1047() {
        val denver = Conditions.ISA.copy(altitudeMeters = 1609.0, temperatureC = 4.5)

        assertThat(AirDensity.of(denver)).isCloseTo(1.047, 0.01)
    }

    @Test
    fun hotterAirIsLessDense() {
        val cool = AirDensity.of(Conditions.ISA.copy(temperatureC = 5.0))
        val hot = AirDensity.of(Conditions.ISA.copy(temperatureC = 35.0))

        assertThat(hot).isLessThan(cool)
    }

    @Test
    fun humidAirIsSlightlyLessDense() {
        val dry = AirDensity.of(Conditions.ISA.copy(temperatureC = 30.0, humidityPct = 0.0))
        val humid = AirDensity.of(Conditions.ISA.copy(temperatureC = 30.0, humidityPct = 90.0))

        assertThat(humid).isLessThan(dry)
        assertThat(humid).isGreaterThan(dry * 0.98)
    }

    @Test
    fun stationPressureWinsOverAltitude() {
        val reported = Conditions.ISA.copy(altitudeMeters = 1609.0, pressureHpa = 1013.25)

        assertThat(AirDensity.of(reported)).isCloseTo(1.225, 0.002)
    }
}
