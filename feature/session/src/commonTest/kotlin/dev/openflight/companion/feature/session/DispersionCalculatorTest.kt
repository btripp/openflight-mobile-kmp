// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.single
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.OfflineDistanceEstimator
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.test.Test

class DispersionCalculatorTest {
    @Test
    fun aPiShotsOfflineMatchesTheSharedEstimateIncludingItsSpinSource() {
        // A calculated spin is swapped for the club's typical one, as the bag and shot sheet do.
        val detail =
            ShotDetail(
                timestamp = "2026-10-13T10:00:00",
                club = "driver",
                ballSpeedMph = 150.0,
                estimatedCarryYards = 240.0,
                spinRpm = 3850.0,
                spinAxisDeg = 10.0,
                spinSource = "calculated",
            )
        val measurements = detail.toFlightMeasurements()!!
        val estimator = OfflineDistanceEstimator()
        val shared = estimator.estimate(measurements)!!.offlineYards

        val point = DispersionCalculator().piPoints(listOf(detail)).single()

        assertThat(point.offlineYards).isEqualTo(shared)
        assertThat(estimator.estimate(measurements.copy(spinSource = null)))
            .isNotNull()
            .transform { it.offlineYards }
            .isNotEqualTo(shared)
    }
}
