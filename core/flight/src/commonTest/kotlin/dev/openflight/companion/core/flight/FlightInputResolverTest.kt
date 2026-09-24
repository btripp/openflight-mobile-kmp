// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Ported one-to-one from `ios/OpenFlightTests/FlightInputResolverTests.swift`. */
class FlightInputResolverTest {
    private val resolver = FlightInputResolver()

    @Test
    fun measuredValuesArePreserved() {
        val input = resolver.resolve(makeDrivingRangeShot())

        assertThat(input.launchAngleDegrees).isEqualTo(12.6)
        assertThat(input.horizontalLaunchDegrees).isEqualTo(-1.3)
        assertThat(input.spinRpm).isEqualTo(2_380.0)
        assertThat(input.spinAxisDegrees).isEqualTo(-3.4)
        assertThat(input.provenance.estimatedParameters).isEmpty()
        assertThat(input.provenance.clampedParameters).isEmpty()
    }

    @Test
    fun missingDriverMeasurementsUseExplicitDefaults() {
        val input =
            resolver.resolve(
                makeDrivingRangeShot(
                    launchAngle = null,
                    horizontalLaunch = null,
                    spinRpm = null,
                    spinAxis = null,
                ),
            )

        assertThat(input.launchAngleDegrees).isEqualTo(12.0)
        assertThat(input.horizontalLaunchDegrees).isEqualTo(0.0)
        assertThat(input.spinRpm).isEqualTo(2_500.0)
        assertThat(input.spinAxisDegrees).isEqualTo(0.0)
        assertThat(input.provenance.estimatedParameters).isEqualTo(FlightParameter.entries.toSet())
        assertThat(input.provenance.usesEstimatedFlight).isTrue()
    }

    @Test
    fun clubDefaultTableCoversEveryClubFamily() {
        val cases =
            listOf(
                Triple("3-wood", 15.0, 3_500.0),
                Triple("5_hybrid", 18.0, 4_200.0),
                Triple("iron_3", 17.0, 4_500.0),
                Triple("7-iron", 21.0, 5_500.0),
                Triple("iron_9", 26.0, 7_000.0),
                Triple("sw", 31.0, 8_500.0),
                Triple("unknown", 18.0, 4_500.0),
            )

        for ((club, expectedLaunch, expectedSpin) in cases) {
            val input = resolver.resolve(makeDrivingRangeShot(club = club, launchAngle = null, spinRpm = null))
            assertThat(input.launchAngleDegrees, club).isEqualTo(expectedLaunch)
            assertThat(input.spinRpm, club).isEqualTo(expectedSpin)
        }
    }

    @Test
    fun extremeMeasurementsAreClampedAndRecorded() {
        val input =
            resolver.resolve(
                makeDrivingRangeShot(
                    launchAngle = 90.0,
                    horizontalLaunch = -80.0,
                    spinRpm = 18_000.0,
                    spinAxis = 92.0,
                ),
            )

        assertThat(input.launchAngleDegrees).isEqualTo(55.0)
        assertThat(input.horizontalLaunchDegrees).isEqualTo(-45.0)
        assertThat(input.spinRpm).isEqualTo(12_000.0)
        assertThat(input.spinAxisDegrees).isEqualTo(60.0)
        assertThat(input.provenance.clampedParameters).isEqualTo(FlightParameter.entries.toSet())
    }

    @Test
    fun nonFiniteOptionalMeasurementUsesFallback() {
        val input =
            resolver.resolve(makeDrivingRangeShot(launchAngle = Double.NaN, spinRpm = Double.POSITIVE_INFINITY))

        assertThat(input.launchAngleDegrees).isEqualTo(12.0)
        assertThat(input.spinRpm).isEqualTo(2_500.0)
        assertThat(input.provenance.estimatedParameters)
            .isEqualTo(setOf(FlightParameter.LAUNCH_ANGLE, FlightParameter.SPIN_RATE))
    }

    @Test
    fun rejectsInvalidBallSpeedAndCarry() {
        assertFailsWith<FlightInputResolutionError.InvalidBallSpeed> {
            resolver.resolve(makeDrivingRangeShot(ballSpeedMph = 0.0))
        }
        assertFailsWith<FlightInputResolutionError.InvalidCarry> {
            resolver.resolve(makeDrivingRangeShot(carryYards = Double.NEGATIVE_INFINITY))
        }
    }
}
