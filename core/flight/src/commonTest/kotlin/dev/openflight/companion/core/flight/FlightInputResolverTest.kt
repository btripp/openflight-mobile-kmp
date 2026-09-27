// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Ported from `ios/OpenFlightTests/FlightInputResolverTests.swift`. Plan F2b moved the per-club
 * defaults onto the backend's `CLUB_PHYSICS` (with its launch-per-mph speed term), so the default
 * values below are the backend's, not the reference's.
 */
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

        // CLUB_PHYSICS driver: 11° at 143 mph, −0.15°/mph, so 151.4 mph → 9.7°. Tour spin 2700.
        assertThat(input.launchAngleDegrees).isEqualTo(9.7)
        assertThat(input.horizontalLaunchDegrees).isEqualTo(0.0)
        assertThat(input.spinRpm).isEqualTo(2_700.0)
        assertThat(input.spinAxisDegrees).isEqualTo(0.0)
        assertThat(input.provenance.estimatedParameters).isEqualTo(FlightParameter.entries.toSet())
        assertThat(input.provenance.usesEstimatedFlight).isTrue()
    }

    @Test
    fun clubDefaultTableCoversEveryClubFamily() {
        // (club, the backend's average ball speed, so launch = the optimal launch) → CLUB_PHYSICS.
        val cases =
            listOf(
                ClubDefault("3-wood", 135.0, 12.5, 3_500.0),
                ClubDefault("5_hybrid", 118.0, 15.0, 4_900.0),
                ClubDefault("iron_3", 118.0, 14.5, 4_500.0),
                ClubDefault("7-iron", 100.0, 20.5, 6_500.0),
                ClubDefault("iron_9", 88.0, 25.5, 8_500.0),
                ClubDefault("PW", 82.0, 28.0, 9_000.0),
                ClubDefault("sw", 73.0, 32.0, 10_000.0),
                ClubDefault("unknown", 120.0, 18.0, 5_000.0),
            )

        for (case in cases) {
            val shot =
                makeDrivingRangeShot(club = case.club, ballSpeedMph = case.speed, launchAngle = null, spinRpm = null)
            val input = resolver.resolve(shot)
            assertThat(input.launchAngleDegrees, case.club).isEqualTo(case.launch)
            assertThat(input.spinRpm, case.club).isEqualTo(case.spin)
        }
    }

    @Test
    fun defaultLaunchRisesForSlowerShotsAndFallsForFasterOnesLikeTheBackend() {
        fun launch(speed: Double) =
            resolver
                .resolve(makeDrivingRangeShot(club = "7-iron", ballSpeedMph = speed, launchAngle = null))
                .launchAngleDegrees

        // 7-iron: 20.5° at 100 mph, 0.30°/mph (server.py estimate_launch_angle), never below 5°.
        assertThat(launch(90.0)).isEqualTo(23.5)
        assertThat(launch(110.0)).isEqualTo(17.5)
        assertThat(launch(200.0)).isEqualTo(5.0)
    }

    private data class ClubDefault(
        val club: String,
        val speed: Double,
        val launch: Double,
        val spin: Double,
    )

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

        assertThat(input.launchAngleDegrees).isEqualTo(9.7)
        assertThat(input.spinRpm).isEqualTo(2_700.0)
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
