// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.FlightGoldens.METERS_PER_YARD
import dev.openflight.companion.core.flight.FlightGoldens.carryYards
import dev.openflight.companion.core.flight.FlightGoldens.landingAngleDegrees
import dev.openflight.companion.core.flight.FlightGoldens.lateralYards
import dev.openflight.companion.core.flight.FlightGoldens.launchAngleDegrees
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.TimeSource

/** Plan F2b: the drag-scale carry fit, curve, drawn-vs-displayed carry and spin provenance. */
class AccurateFlightTest {
    private val tourDriver = TrackManTourAverages.PGA_2009.first { it.club == "driver" }
    private val tourSevenIron = TrackManTourAverages.PGA_2009.first { it.club == "7-iron" }
    private val tourWedge = TrackManTourAverages.PGA_2009.first { it.club == "pw" }

    // region Carry fit

    @Test
    fun dragFitHitsTheTargetWithinATenthOfAYardAndKeepsTheLaunchAngle() {
        for (row in listOf(tourDriver, tourSevenIron, tourWedge)) {
            val base = FlightGoldens.unconstrained.simulate(FlightGoldens.input(row))
            for (factor in listOf(0.90, 0.95, 1.05, 1.10)) {
                val target = base.carryYards * factor
                val input = FlightGoldens.input(row).copy(targetCarryMeters = target * METERS_PER_YARD)
                val fitted = FlightGoldens.render.simulate(input)
                val fit = checkNotNull(fitted.carryFit)
                val name = "${row.club} ×$factor"

                // The fit itself lands within 0.1 yd; the residual scale closes the rest exactly.
                assertThat(abs(fit.residualScale - 1.0) * target, name).isLessThan(0.1)
                assertThat(fitted.carryYards, name).isCloseTo(target, 1e-6)
                assertThat(
                    fit.dragScale,
                    name,
                ).isBetween(BallFlightSimulator.DRAG_SCALE_MIN, BallFlightSimulator.DRAG_SCALE_MAX)
                assertThat(fitted.launchAngleDegrees, name).isCloseTo(row.launchDegrees, 0.1)
                assertThat(fitted.landingAngleDegrees, name).isCloseTo(base.landingAngleDegrees, 1.5)
            }
        }
    }

    @Test
    fun dragFitDirectionFollowsTheTarget() {
        val base = FlightGoldens.unconstrained.simulate(FlightGoldens.input(tourSevenIron))

        fun fitFor(factor: Double) =
            FlightGoldens.render
                .simulate(
                    FlightGoldens.input(tourSevenIron).copy(targetCarryMeters = base.carryMeters * factor),
                ).carryFit!!

        assertThat(fitFor(0.9).dragScale).isGreaterThan(1.0)
        assertThat(fitFor(1.1).dragScale).isLessThan(1.0)
        assertThat(fitFor(1.0).dragScale).isEqualTo(1.0)
        assertThat(fitFor(0.97).shapeEstimated).isFalse()
    }

    @Test
    fun beyondTheClampTheResidualIsScaledAndFlagged() {
        val base = FlightGoldens.unconstrained.simulate(FlightGoldens.input(tourDriver))
        val long = FlightGoldens.input(tourDriver).copy(targetCarryMeters = base.carryMeters * 1.6)
        val short = FlightGoldens.input(tourDriver).copy(targetCarryMeters = base.carryMeters * 0.6)

        val longFit = FlightGoldens.render.simulate(long)
        val shortFit = FlightGoldens.render.simulate(short)

        assertThat(longFit.carryFit!!.dragScale).isEqualTo(BallFlightSimulator.DRAG_SCALE_MIN)
        assertThat(longFit.carryFit!!.residualScale).isGreaterThan(1.0)
        assertThat(longFit.carryFit!!.shapeEstimated).isTrue()
        assertThat(longFit.carryMeters).isCloseTo(long.targetCarryMeters, 1e-6)
        assertThat(shortFit.carryFit!!.dragScale).isEqualTo(BallFlightSimulator.DRAG_SCALE_MAX)
        assertThat(shortFit.carryFit!!.residualScale).isLessThan(1.0)
        assertThat(shortFit.carryMeters).isCloseTo(short.targetCarryMeters, 1e-6)
    }

    @Test
    fun fitToCarryFromAConditionsRunEqualsSimulatingFromScratch() {
        val input = FlightGoldens.input(tourSevenIron).copy(targetCarryMeters = 160.0 * METERS_PER_YARD)
        val conditionsRun = BallFlightSimulator(BallFlightSimulator.Configuration.conditions).simulate(input)

        val reused = FlightGoldens.render.fitToCarry(input, conditionsRun)
        val scratch = FlightGoldens.render.simulate(input)

        assertThat(reused).isEqualTo(scratch)
    }

    // endregion

    // region Curve

    @Test
    fun aSevenIronWithATenDegreeAxisCurvesSixToElevenPercentOfCarry() {
        val input = FlightGoldens.input(tourSevenIron).copy(spinAxisDegrees = 10.0)
        val flight = FlightGoldens.unconstrained.simulate(input)

        // TrackMan's rule of thumb is about 7 % at 150 yd for a 10° axis.
        assertThat(flight.lateralYards / flight.carryYards).isBetween(0.06, 0.11)
    }

    @Test
    fun aDriverWithATenDegreeAxisCurvesAtLeastFivePercentOfCarry() {
        val input = FlightGoldens.input(tourDriver).copy(spinAxisDegrees = 10.0)
        val flight = FlightGoldens.render.simulate(input)

        // The reference renderer's lift gave 1.9 % here (about 4× too straight).
        assertThat(flight.lateralYards / flight.carryYards).isGreaterThan(0.05)
    }

    @Test
    fun withoutASpinAxisTheBallStaysOnItsStartLine() {
        val shot = ConditionsLaunches.DRIVER.copy(launchAngleHorizontal = 2.0, spinAxisDeg = null)
        val input = FlightInputResolver().resolve(shot)
        val flight = FlightGoldens.render.simulate(input)

        assertThat(input.spinAxisDegrees).isEqualTo(0.0)
        assertThat(input.provenance.estimatedParameters).contains(FlightParameter.SPIN_AXIS)
        // Close to the 2° start line (the axis is perpendicular to downrange, not to the launch
        // direction, so a small in-plane lift component stays downrange; under 10° HLA it's tiny).
        val startLine = flight.carryYards * kotlin.math.tan(2.0 * kotlin.math.PI / 180)
        println("start line $startLine lateral ${flight.lateralYards}")
        assertThat(flight.lateralYards).isCloseTo(startLine, 1.0)
    }

    // endregion

    // region Drawn carry == displayed carry

    private val planner = ShotFlightPlanner()

    @Test
    fun theDrawnLandingIsTheDisplayedCarry() {
        val mileHigh = Conditions.ISA.copy(altitudeMeters = 1_609.0)
        val windy = Conditions.ISA.copy(wind = Wind(speedMps = 6.0, fromDegrees = 270.0))
        val shots =
            listOf(
                ConditionsLaunches.DRIVER,
                ConditionsLaunches.SEVEN_IRON,
                ConditionsLaunches.PITCHING_WEDGE,
                ConditionsLaunches.DRIVER.copy(carrySpinAdjustedYards = 231.0),
            )
        for (shot in shots) {
            for ((conditions, bearing) in listOf(
                Conditions.ISA to null,
                mileHigh to null,
                windy to TargetBearing(0.0),
            )) {
                val plan = assertNotNull(planner.plan(shot, conditions, bearing))
                val name = "${shot.club} ${conditions.altitudeMeters} $bearing"

                assertThat(plan.trajectory.carryYards, name).isCloseTo(plan.estimate.carryYards, 1e-6)
                assertThat(
                    plan.trajectory.points
                        .first()
                        .positionMeters.z,
                    name,
                ).isEqualTo(0.0)
                assertThat(plan.trajectory.launchAngleDegrees, name).isCloseTo(shot.launchAngleVertical!!, 0.1)
            }
        }
    }

    @Test
    fun aCrosswindWithABearingMovesTheDrawnLanding() {
        // Wind from the left (270° with the target at 0°) blows the ball right.
        val windy = Conditions.ISA.copy(wind = Wind(speedMps = 6.0, fromDegrees = 270.0))
        val calm = assertNotNull(planner.plan(ConditionsLaunches.DRIVER, Conditions.ISA, TargetBearing(0.0)))
        val noBearing = assertNotNull(planner.plan(ConditionsLaunches.DRIVER, windy, null))
        val withBearing = assertNotNull(planner.plan(ConditionsLaunches.DRIVER, windy, TargetBearing(0.0)))
        val drift = withBearing.trajectory.lateralYards - calm.trajectory.lateralYards

        assertThat(noBearing.trajectory.lateralYards).isCloseTo(calm.trajectory.lateralYards, 1e-9)
        assertThat(withBearing.estimate.lateralDriftYards).isGreaterThan(1.0)
        assertThat(drift).isCloseTo(
            withBearing.estimate.lateralDriftYards,
            withBearing.estimate.lateralDriftYards * 0.25,
        )
    }

    @Test
    fun theRenderedDriverHangsLikeATourDriveAndLandsSteeply() {
        val shot =
            ConditionsLaunches.measured(
                club = "driver",
                speedMph = tourDriver.ballSpeedMph,
                launch = tourDriver.launchDegrees,
                spin = tourDriver.spinRpm,
                carry = tourDriver.carryYards,
            )
        val plan = assertNotNull(planner.plan(shot, Conditions.ISA, null))

        // The reference renderer: about 3.75 s, a 13 yd apex and a ~15° descent after its rescale.
        assertThat(plan.trajectory.flightTime).isBetween(6.3, 7.5)
        assertThat(plan.trajectory.landingAngleDegrees).isBetween(34.0, 42.0)
    }

    @Test
    fun hundredPlansAllComplete() {
        val windy = Conditions.ISA.copy(altitudeMeters = 1_609.0, wind = Wind(speedMps = 4.0, fromDegrees = 200.0))
        val shots =
            (0 until 100).map { index ->
                ConditionsLaunches.DRIVER.copy(id = "shot-$index", ballSpeedMph = 120.0 + index * 0.4)
            }
        planner.plan(shots.first(), windy, TargetBearing(10.0))

        val mark = TimeSource.Monotonic.markNow()
        val plans = shots.mapNotNull { planner.plan(it, windy, TargetBearing(10.0)) }
        val elapsed = mark.elapsedNow()
        println("ShotFlightPlanner: 100 shots in $elapsed (${elapsed.inWholeMicroseconds / 100} µs/shot)")

        assertThat(plans.size).isEqualTo(100)
        // Wall-clock time is logged, not asserted: it depends on the machine (GitHub's macOS runner
        // running the debug iOS simulator is several times slower than a dev Mac). The cost per shot
        // is bounded structurally by the fit's MAXIMUM_FIT_ITERATIONS; rendering speed is covered by
        // the device-level overlay perf tests.
    }

    // endregion

    // region Spin provenance

    private val resolver = FlightInputResolver()

    @Test
    fun calculatedOrClubTypicalSpinIsEstimated() {
        for (source in listOf("calculated", "club_typical")) {
            val input = resolver.resolve(ConditionsLaunches.SEVEN_IRON.copy(spinSource = source))

            assertThat(input.spinRpm, source).isEqualTo(6_500.0)
            assertThat(input.provenance.estimatedParameters, source).contains(FlightParameter.SPIN_RATE)
            assertThat(input.provenance.usesEstimatedFlight, source).isTrue()
        }
    }

    @Test
    fun measuredOrUnlabelledSpinIsMeasured() {
        for (source in listOf("measured", null)) {
            val input = resolver.resolve(ConditionsLaunches.SEVEN_IRON.copy(spinSource = source))

            assertThat(input.provenance.estimatedParameters).doesNotContain(FlightParameter.SPIN_RATE)
        }
    }

    @Test
    fun driversAndWoodsReplaceCalculatedSpinWithTheClubTypicalSpin() {
        val driver = resolver.resolve(ConditionsLaunches.DRIVER.copy(spinRpm = 3_850.0, spinSource = "calculated"))
        val wood =
            resolver.resolve(
                ConditionsLaunches.DRIVER.copy(club = "3-wood", spinRpm = 4_600.0, spinSource = "calculated"),
            )
        val measured = resolver.resolve(ConditionsLaunches.DRIVER.copy(spinRpm = 3_850.0, spinSource = "measured"))

        assertThat(driver.spinRpm).isEqualTo(2_700.0)
        assertThat(driver.provenance.estimatedParameters).contains(FlightParameter.SPIN_RATE)
        assertThat(wood.spinRpm).isEqualTo(3_500.0)
        assertThat(measured.spinRpm).isEqualTo(3_850.0)
    }

    @Test
    fun theEstimateFlagsEstimatedSpin() {
        val estimate =
            assertNotNull(
                ShotDistanceEstimator().estimate(
                    ConditionsLaunches.SEVEN_IRON.copy(spinSource = "calculated"),
                    Conditions.ISA,
                    null,
                ),
            )

        assertThat(estimate.spinEstimated).isTrue()
    }

    @Test
    fun spinSourceTravelsFromTheWireIntoTheMeasurements() {
        val shot = makeDrivingRangeShot().copy(spinSource = "calculated")
        val row =
            ShotDetail(
                timestamp = "2026-09-26T10:00:00",
                ballSpeedMph = 120.0,
                estimatedCarryYards = 165.0,
                club = "7-iron",
                spinRpm = 7_000.0,
                spinSource = "club_typical",
            )

        assertThat(shot.toFlightMeasurements().spinSource).isEqualTo("calculated")
        assertThat(assertNotNull(row.toFlightMeasurements()).spinSource).isEqualTo("club_typical")
    }

    // endregion

    private fun <T : Any> assertNotNull(value: T?): T {
        assertThat(value).isNotNull()
        return checkNotNull(value)
    }
}
