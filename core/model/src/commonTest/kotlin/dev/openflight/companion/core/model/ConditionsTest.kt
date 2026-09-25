// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class ConditionsTest {
    @Test
    fun isaIsSeaLevelFifteenDegreesCalmNormal() {
        val isa = Conditions.ISA

        assertThat(isa.altitudeMeters).isEqualTo(0.0)
        assertThat(isa.temperatureC).isEqualTo(15.0)
        assertThat(isa.humidityPct).isNull()
        assertThat(isa.pressureHpa).isNull()
        assertThat(isa.wind.isCalm).isTrue()
        assertThat(isa.surface).isEqualTo(Firmness.NORMAL)
    }

    @Test
    fun bearingNormalizesIntoOneTurn() {
        assertThat(TargetBearing(-90.0).normalizedDegrees).isCloseTo(270.0, 1e-9)
        assertThat(TargetBearing(725.0).normalizedDegrees).isCloseTo(5.0, 1e-9)
    }

    @Test
    fun anyWindSpeedIsNotCalm() {
        assertThat(Wind(speedMps = 0.5, fromDegrees = 10.0).isCalm).isFalse()
    }
}
