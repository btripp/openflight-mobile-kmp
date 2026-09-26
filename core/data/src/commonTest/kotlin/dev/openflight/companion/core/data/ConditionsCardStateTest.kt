// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.location.LocationResult
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConditionsSource
import kotlin.test.Test

/** [conditionsCardState], the pure mapper F5/F8a2 both reuse (plan F6 task 4). */
class ConditionsCardStateTest {
    @Test
    fun manualConditionsHaveNoLocationLabelOrEstimatedBadge() {
        val state = conditionsCardState(Conditions.ISA, ConditionsMode.MANUAL, lastError = null, lastLocation = null)

        assertThat(state.isAuto).isFalse()
        assertThat(state.isEstimatedSource).isFalse()
        assertThat(state.locationLabel).isNull()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun autoConditionsWithAFixShowARoundedLatLonLabel() {
        val weather = Conditions.ISA.copy(source = ConditionsSource.WEATHER)
        val fix = LocationResult.Fix(lat = 39.73916, lon = -104.99031, altitudeM = null, accuracyM = 50.0)

        val state = conditionsCardState(weather, ConditionsMode.AUTO, lastError = null, lastLocation = fix)

        assertThat(state.isAuto).isTrue()
        assertThat(state.isEstimatedSource).isTrue()
        assertThat(state.locationLabel).isEqualTo("near 39.74, -104.99")
    }

    @Test
    fun aManualFallbackWithAFixStillShowsNoLabel() {
        // setMode(AUTO) can fall back to MANUAL on a denied permission; the fix from an earlier
        // successful fetch (if any) shouldn't be attributed to the manual value shown now.
        val fix = LocationResult.Fix(lat = 1.0, lon = 2.0, altitudeM = null, accuracyM = 10.0)

        val state = conditionsCardState(Conditions.ISA, ConditionsMode.MANUAL, lastError = null, lastLocation = fix)

        assertThat(state.locationLabel).isNull()
    }

    @Test
    fun anErrorMessageComesThroughVerbatim() {
        val state =
            conditionsCardState(
                Conditions.ISA,
                ConditionsMode.MANUAL,
                lastError = ConditionsError.LocationPermissionDenied,
                lastLocation = null,
            )

        assertThat(state.errorMessage).isEqualTo(ConditionsError.LocationPermissionDenied.userMessage)
    }

    @Test
    fun theAttributionIsAlwaysTheOpenMeteoCredit() {
        val state = conditionsCardState(Conditions.ISA, ConditionsMode.MANUAL, lastError = null, lastLocation = null)

        assertThat(state.attribution).isEqualTo("Weather data by Open-Meteo.com (CC BY 4.0)")
    }
}
