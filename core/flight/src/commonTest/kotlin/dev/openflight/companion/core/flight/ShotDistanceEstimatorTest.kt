// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.test.Test
import kotlin.time.TimeSource

class ShotDistanceEstimatorTest {
    private val estimator = ShotDistanceEstimator()
    private val mileHigh = Conditions.ISA.copy(altitudeMeters = 1609.0)

    @Test
    fun isaKeepsTheServerCarryAndAddsAnEstimatedRoll() {
        val estimate = assertNotNull(estimator.estimate(ConditionsLaunches.DRIVER, Conditions.ISA, null))

        assertThat(estimate.carryYards).isEqualTo(225.0)
        assertThat(estimate.isAdjusted).isFalse()
        assertThat(estimate.carryProvenance).isEqualTo(DistanceProvenance.SERVER)
        assertThat(estimate.rollProvenance).isEqualTo(DistanceProvenance.ESTIMATED)
        assertThat(estimate.rollYards).isGreaterThan(0.0)
        assertThat(estimate.totalYards).isCloseTo(estimate.carryYards + estimate.rollYards, 1e-9)
    }

    @Test
    fun altitudeAdjustsTheCarry() {
        val estimate = assertNotNull(estimator.estimate(ConditionsLaunches.DRIVER, mileHigh, null))

        assertThat(estimate.isAdjusted).isTrue()
        assertThat(estimate.carryProvenance).isEqualTo(DistanceProvenance.ESTIMATED)
        assertThat(estimate.carryYards).isGreaterThan(225.0)
    }

    @Test
    fun spinAdjustedCarryIsThePreferredAnchor() {
        val shot = ConditionsLaunches.DRIVER.copy(carrySpinAdjustedYards = 240.0)

        val estimate = assertNotNull(estimator.estimate(shot, Conditions.ISA, null))

        assertThat(estimate.anchor).isEqualTo(CarryAnchor.SPIN_ADJUSTED)
        assertThat(estimate.anchorCarryYards).isEqualTo(240.0)
        assertThat(estimate.carryYards).isEqualTo(240.0)
    }

    @Test
    fun zeroSpinAdjustedCarryFallsBackToTheTableCarry() {
        val shot = ConditionsLaunches.DRIVER.copy(carrySpinAdjustedYards = 0.0)

        val estimate = assertNotNull(estimator.estimate(shot, Conditions.ISA, null))

        assertThat(estimate.anchor).isEqualTo(CarryAnchor.TABLE)
        assertThat(estimate.anchorCarryYards).isEqualTo(225.0)
    }

    @Test
    fun resolvedSpinIsFlaggedEstimated() {
        val shot = ConditionsLaunches.DRIVER.copy(spinRpm = null)

        val estimate = assertNotNull(estimator.estimate(shot, Conditions.ISA, null))

        assertThat(estimate.spinEstimated).isTrue()
    }

    @Test
    fun windWithoutABearingAsksForOne() {
        val windy = Conditions.ISA.copy(wind = Wind(speedMps = 6.0, fromDegrees = 0.0))

        val withoutBearing = assertNotNull(estimator.estimate(ConditionsLaunches.DRIVER, windy, null))
        val withBearing = assertNotNull(estimator.estimate(ConditionsLaunches.DRIVER, windy, TargetBearing(0.0)))

        assertThat(withoutBearing.windNeedsTargetBearing).isTrue()
        assertThat(withoutBearing.windApplied).isFalse()
        assertThat(withoutBearing.carryYards).isEqualTo(225.0)
        assertThat(withBearing.windNeedsTargetBearing).isFalse()
        assertThat(withBearing.windApplied).isTrue()
        assertThat(withBearing.carryYards).isLessThan(225.0)
    }

    @Test
    fun unusableShotHasNoEstimate() {
        val shot = ConditionsLaunches.DRIVER.copy(ballSpeedMph = 0.0)

        assertThat(estimator.estimate(shot, Conditions.ISA, null)).isNull()
    }

    @Test
    fun historyRowsCarryTheSpinAdjustedAnchor() {
        val row =
            ShotDetail(
                timestamp = "2026-09-25T10:00:00",
                ballSpeedMph = 143.0,
                estimatedCarryYards = 225.0,
                club = "driver",
                launchAngleVertical = 11.0,
                spinRpm = 2_700.0,
                carrySpinAdjusted = 231.0,
            )

        val measurements = assertNotNull(row.toFlightMeasurements())

        assertThat(measurements.id).isEqualTo("2026-09-25T10:00:00")
        assertThat(measurements.carrySpinAdjustedYards).isEqualTo(231.0)
        assertThat(row.copy(ballSpeedMph = null).toFlightMeasurements()).isNull()
    }

    @Test
    fun hundredEstimatesStayFast() {
        val shots =
            (0 until 100).map { index ->
                ConditionsLaunches.DRIVER.copy(id = "shot-$index", ballSpeedMph = 120.0 + index * 0.4)
            }
        val windy = mileHigh.copy(wind = Wind(speedMps = 4.0, fromDegrees = 200.0))

        val mark = TimeSource.Monotonic.markNow()
        val estimates = shots.mapNotNull { estimator.estimate(it, windy, TargetBearing(10.0)) }
        val elapsed = mark.elapsedNow()
        println("ShotDistanceEstimator: 100 shots in $elapsed (${elapsed.inWholeMicroseconds / 100} µs/shot)")

        assertThat(estimates.size).isEqualTo(100)
        assertThat(elapsed.inWholeMilliseconds).isLessThan(2_000L)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertThat(value).isNotNull()
        return checkNotNull(value)
    }
}
