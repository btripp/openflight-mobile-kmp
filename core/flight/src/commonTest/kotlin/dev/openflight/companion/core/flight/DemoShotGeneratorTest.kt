// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotEqualTo
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Plan F14: Demo mode's made-up shots are deterministic for a seed, vary naturally, and carry
 * sensible distances for the club (amateur ball speeds, so shorter than the tour's).
 */
class DemoShotGeneratorTest {
    private fun sample(
        club: String,
        count: Int = SAMPLES,
        seed: Long = 7L,
    ): List<DemoShot> {
        val generator = DemoShotGenerator(seed)
        return List(count) { generator.next(club) }
    }

    private fun List<Double>.mean(): Double = sum() / size

    private fun List<Double>.standardDeviation(): Double {
        val mean = mean()
        return sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
    }

    @Test
    fun sameSeedGivesTheSameShots() {
        assertThat(sample("7-iron", count = 10, seed = 42L)).isEqualTo(sample("7-iron", count = 10, seed = 42L))
    }

    @Test
    fun anotherSeedGivesOtherShots() {
        assertThat(sample("7-iron", count = 10, seed = 1L)).isNotEqualTo(sample("7-iron", count = 10, seed = 2L))
    }

    @Test
    fun consecutiveShotsVary() {
        val shots = sample("driver", count = 20)
        assertThat(shots.map { it.carryYards }.distinct().size).isGreaterThan(15)
        assertThat(shots.map { it.carryYards }.standardDeviation()).isBetween(2.0, 20.0)
        assertThat(shots.map { it.launchAngleHorizontal }.standardDeviation()).isBetween(0.5, 5.0)
        assertThat(shots.map { it.spinAxisDeg }.standardDeviation()).isBetween(1.0, 10.0)
    }

    @Test
    fun driverCarriesLikeAnAmateurDriver() {
        val carry = sample("driver").map { it.carryYards }
        println(
            "driver carry mean ${carry.mean()} sd ${carry.standardDeviation()} min ${carry.min()} max ${carry.max()}",
        )
        assertThat(carry.mean()).isBetween(200.0, 250.0)
        assertThat(carry.min()).isGreaterThan(150.0)
        assertThat(carry.max()).isLessThan(280.0)
    }

    @Test
    fun sevenIronCarriesLikeAnAmateurSevenIron() {
        val carry = sample("7-iron").map { it.carryYards }
        println(
            "7-iron carry mean ${carry.mean()} sd ${carry.standardDeviation()} min ${carry.min()} max ${carry.max()}",
        )
        assertThat(carry.mean()).isBetween(125.0, 160.0)
        assertThat(carry.min()).isGreaterThan(95.0)
        assertThat(carry.max()).isLessThan(180.0)
    }

    @Test
    fun pitchingWedgeCarriesLikeAnAmateurWedge() {
        val carry = sample("pw").map { it.carryYards }
        println("pw carry mean ${carry.mean()} sd ${carry.standardDeviation()} min ${carry.min()} max ${carry.max()}")
        assertThat(carry.mean()).isBetween(90.0, 125.0)
        assertThat(carry.min()).isGreaterThan(65.0)
        assertThat(carry.max()).isLessThan(140.0)
    }

    @Test
    fun carryFallsThroughTheBag() {
        val clubs = listOf("driver", "3-wood", "5-iron", "7-iron", "9-iron", "pw", "sw")
        val means = clubs.map { club -> sample(club).map { it.carryYards }.mean() }
        println("means ${clubs.zip(means)}")
        means.zipWithNext().forEach { (longer, shorter) -> assertThat(longer).isGreaterThan(shorter) }
    }

    @Test
    fun theNumbersHangTogether() {
        sample("6-iron", count = 50).forEach { shot ->
            // Smash factor is ball speed over club speed, within rounding.
            assertThat(shot.ballSpeedMph / shot.clubSpeedMph).isCloseTo(shot.smashFactor, 0.01)
            // The server's relation: spin axis = HLA − club path.
            assertThat(shot.launchAngleHorizontal - shot.clubPathDeg).isCloseTo(shot.spinAxisDeg, 0.11)
            assertThat(abs(shot.spinAxisDeg)).isLessThan(15.01)
            assertThat(shot.spinRpm).isBetween(1_200.0, 11_500.0)
        }
    }

    @Test
    fun theCarryIsTheSimulatedFlightOfTheShot() {
        val shot = sample("8-iron", count = 1).single()
        val flight =
            BallFlightSimulator(BallFlightSimulator.Configuration.conditions).simulate(
                FlightInput(
                    eventId = "check",
                    ballSpeedMetersPerSecond = shot.ballSpeedMph * 0.44704,
                    launchAngleDegrees = shot.launchAngleVertical,
                    horizontalLaunchDegrees = shot.launchAngleHorizontal,
                    spinRpm = shot.spinRpm,
                    spinAxisDegrees = shot.spinAxisDeg,
                    targetCarryMeters = 0.0,
                    provenance = FlightInputProvenance(),
                ),
            )
        // The shot's numbers are rounded to tenths, so its flight lands within about a yard.
        assertThat(flight.carryMeters / 0.9144).isCloseTo(shot.carryYards, 1.5)
    }

    private companion object {
        const val SAMPLES = 200
    }
}
