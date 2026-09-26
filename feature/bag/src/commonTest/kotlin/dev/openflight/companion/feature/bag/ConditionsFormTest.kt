// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlin.test.Test

class ConditionsFormTest {
    @Test
    fun isaPrefillsInImperialUnits() {
        val form = ConditionsForm.of(Conditions.ISA, null, UnitSystem.IMPERIAL)

        assertThat(form).isEqualTo(
            ConditionsForm(
                altitude = "0",
                temperature = "59",
                windSpeed = "0",
                windFrom = "0",
                surface = Firmness.NORMAL,
                targetBearing = "",
            ),
        )
    }

    @Test
    fun metricValuesParseAsIs() {
        val result =
            ConditionsForm(
                "1609",
                "15",
                "36",
                "180",
                Firmness.SOFT,
                "0",
            ).parse(UnitSystem.METRIC) as ConditionsFormResult.Valid

        assertThat(result.conditions.altitudeMeters).isCloseTo(1609.0, 1e-9)
        assertThat(result.conditions.temperatureC).isCloseTo(15.0, 1e-9)
        assertThat(result.conditions.wind).isEqualTo(Wind(speedMps = 10.0, fromDegrees = 180.0))
        assertThat(result.conditions.surface).isEqualTo(Firmness.SOFT)
        assertThat(result.targetBearing).isEqualTo(TargetBearing(0.0))
    }

    @Test
    fun aBlankTargetDirectionMeansNotSet() {
        val result =
            ConditionsForm(
                "0",
                "59",
                "",
                "",
                Firmness.NORMAL,
                " ",
            ).parse(UnitSystem.IMPERIAL) as ConditionsFormResult.Valid

        assertThat(result.targetBearing).isNull()
        assertThat(result.conditions.wind.isCalm).isEqualTo(true)
    }

    @Test
    fun outOfRangeValuesAreRefused() {
        assertThat(ConditionsForm("9000", "15", "0", "0", Firmness.NORMAL, "").parse(UnitSystem.METRIC))
            .isEqualTo(ConditionsFormResult.Invalid("Altitude must be between -500 m and 5,000 m."))
        assertThat(ConditionsForm("0", "15", "0", "400", Firmness.NORMAL, "").parse(UnitSystem.METRIC))
            .isInstanceOf(ConditionsFormResult.Invalid::class)
        assertThat(ConditionsForm("0", "15", "0", "0", Firmness.NORMAL, "north").parse(UnitSystem.METRIC))
            .isEqualTo(ConditionsFormResult.Invalid("Enter the target direction in degrees."))
    }

    @Test
    fun formRoundTripsThroughTheDisplayUnits() {
        val conditions = Conditions.ISA.copy(altitudeMeters = 1609.344, temperatureC = 30.0, wind = Wind(4.4704, 90.0))
        val form = ConditionsForm.of(conditions, TargetBearing(45.0), UnitSystem.IMPERIAL)

        assertThat(form.altitude).isEqualTo("5280")
        assertThat(form.temperature).isEqualTo("86")
        assertThat(form.windSpeed).isEqualTo("10")
        assertThat(form.targetBearing).isEqualTo("45")
    }

    @Test
    fun compassPointsRoundToTheNearestOfEight() {
        assertThat(BagCopy.compass(0.0)).isEqualTo("N")
        assertThat(BagCopy.compass(350.0)).isEqualTo("N")
        assertThat(BagCopy.compass(100.0)).isEqualTo("E")
        assertThat(BagCopy.compass(225.0)).isEqualTo("SW")
    }
}
