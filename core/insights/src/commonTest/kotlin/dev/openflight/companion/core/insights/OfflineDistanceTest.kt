// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.FlightMeasurements
import kotlin.test.Test

class OfflineDistanceTest {
    private val estimator = OfflineDistanceEstimator()

    @Test
    fun aStraightMeasuredShotLandsOnTheTargetLine() {
        val offline = estimator.estimate(sevenIron(horizontal = 0.0, spinAxis = 0.0))

        assertThat(offline).isNotNull()
        assertThat(offline!!.offlineYards).isCloseTo(0.0, 0.01)
        assertThat(offline.sideEstimated).isFalse()
    }

    @Test
    fun aShotStartedRightLandsRight() {
        val offline = estimator.estimate(sevenIron(horizontal = 3.0, spinAxis = 0.0))!!

        assertThat(offline.offlineYards).isGreaterThan(0.0)
    }

    @Test
    fun aShotStartedLeftLandsLeft() {
        val offline = estimator.estimate(sevenIron(horizontal = -3.0, spinAxis = 0.0))!!

        assertThat(offline.offlineYards).isLessThan(0.0)
    }

    @Test
    fun withoutHorizontalLaunchOrSpinAxisTheSideIsEstimated() {
        val offline = estimator.estimate(sevenIron(horizontal = null, spinAxis = null))!!

        assertThat(offline.sideEstimated).isTrue()
    }

    @Test
    fun oneSideMeasurementIsEnoughForAMeasuredSide() {
        val offline = estimator.estimate(sevenIron(horizontal = 2.0, spinAxis = null))!!

        assertThat(offline.sideEstimated).isFalse()
    }

    @Test
    fun aShotThatCannotBeFlownHasNoOffline() {
        assertThat(estimator.estimate(sevenIron(horizontal = 0.0, spinAxis = 0.0).copy(ballSpeedMph = 0.0))).isNull()
    }

    private fun sevenIron(
        horizontal: Double?,
        spinAxis: Double?,
    ) = FlightMeasurements(
        id = "shot-1",
        club = "7-iron",
        ballSpeedMph = 120.0,
        carryYards = 160.0,
        launchAngleVertical = 17.0,
        launchAngleHorizontal = horizontal,
        spinRpm = 7000.0,
        spinAxisDeg = spinAxis,
    )
}
