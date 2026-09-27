// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * RK4 ball-flight integrator, ported from `ios/OpenFlight/DrivingRange/BallFlightSimulator.swift`
 * and moved onto the backend's aerodynamics by plan F2b.
 *
 * The default [Configuration.render] uses the Ferguson, McNally & McPhee (2022) Cd/Cl polynomials
 * with 0.04/s spin decay, the model behind the server's `carry_spin_adjusted`. Unconstrained, it
 * reproduces the TrackMan 2009 PGA Tour averages to about 2.6 % RMS carry and 1.3 yd RMS apex
 * (`TrackManTourGoldenTest`). The reference renderer's constant-Cd, linear-lift model
 * ([Configuration.standard]) under-lifts a driver about threefold (a 13 yd apex vs TrackMan's 31)
 * and is kept only for the ported reference tests.
 *
 * **Curve.** The spin axis tilts the Magnus force sideways. Without a spin axis the resolver flies
 * axis 0, so the ball stays on its start line (set by the horizontal launch angle) and no curve is
 * invented. Note that the server derives `spin_axis_deg = HLA − club path` (`server.py:3051` at
 * 7ca4b40). By D-plane geometry the true tilt is about `atan(sin(face − path) / tan(spin loft))`,
 * while `HLA − path ≈ k·(face − path)` with k ≈ 0.85 (driver) to 0.75 (irons). So the wire axis
 * understates the curve roughly 4–5× for a driver and 2–3× for mid irons. That is an upstream
 * backend issue to raise; the app draws the axis the wire reports rather than inflating it.
 */
class BallFlightSimulator(
    private val configuration: Configuration = Configuration.render,
) {
    /**
     * How drag and lift coefficients are computed.
     * - [CONSTANT_DRAG_LINEAR_LIFT]: the reference renderer's model (constant
     *   [Configuration.dragCoefficient]; lift = [Configuration.liftSlope] × spin parameter, capped at
     *   [Configuration.maximumLiftCoefficient]).
     * - [SPIN_PARAMETER_POLYNOMIAL]: the backend's model (`ballistics.py` `CD_POLY`/`CL_POLY` at
     *   `7ca4b40`): second-order polynomials in the spin parameter Sp = r·ω/v with the coefficients
     *   published by Ferguson, McNally & McPhee (2022, ISEA 14, doi:10.5703/1288284317493). Sp is
     *   held at 0.75 beyond the fit range and Cl is clamped at ≥ 0. The drag and lift constants of
     *   [Configuration] are ignored in this mode.
     */
    enum class Aerodynamics {
        CONSTANT_DRAG_LINEAR_LIFT,
        SPIN_PARAMETER_POLYNOMIAL,
    }

    /**
     * How a flight is made to land at [FlightInput.targetCarryMeters].
     * - [NONE]: the unconstrained flight.
     * - [SCALE]: the reference's uniform x/z rescale of the whole trajectory. It keeps the apex and
     *   hang time but changes tan(landing angle) by 1/k, so it isn't a solution of the equations of
     *   motion. Only [Configuration.standard] uses it.
     * - [DRAG_FIT]: fit one drag scale k (Cd × k), clamped to [DRAG_SCALE_MIN] .. [DRAG_SCALE_MAX],
     *   until the carry is within [CARRY_FIT_TOLERANCE_METERS] of the target, then close any
     *   residual (a clamped k, or the last few centimetres) with a uniform x/z scale. Carry falls
     *   monotonically and smoothly as drag rises, so a secant search converges in two or three
     *   extra runs (bisection took about ten, too slow for 200 overlay shots on a phone); it keeps the
     *   measured launch angle, horizontal launch and spin exactly. k stands in for what isn't
     *   measured (the ball model, the air, the table carry's error) and is recorded in
     *   [FlightTrajectory.carryFit].
     */
    enum class CarryConstraint {
        NONE,
        SCALE,
        DRAG_FIT,
    }

    /**
     * Tunable physics knobs, ported from `BallFlightSimulator.Configuration`.
     * - [render]: every drawn and measured flight (plan F2b).
     * - [conditions]: the same air and aerodynamics, unconstrained and without resampled frames,
     *   for [ConditionsAdjuster].
     * - [standard]: the reference renderer's model.
     * - [vacuum]: no drag or lift, an independent closed-form oracle in tests.
     *
     * [spinDecayPerSecond] is an exponential spin decay, ω(t) = ω₀·e^(−rate·t), applied between
     * integration steps as the backend does (about 4 %/s, Kiratidis & Leinweber 2018; within
     * 0.4 yd of Smits & Smith's speed-dependent law).
     */
    data class Configuration(
        val timeStep: Double = DEFAULT_TIME_STEP,
        val outputFramesPerSecond: Double = DEFAULT_OUTPUT_FRAMES_PER_SECOND,
        val gravity: Double = DEFAULT_GRAVITY,
        val airDensity: Double = DEFAULT_AIR_DENSITY,
        val dragCoefficient: Double = DEFAULT_DRAG_COEFFICIENT,
        val liftSlope: Double = DEFAULT_LIFT_SLOPE,
        val maximumLiftCoefficient: Double = DEFAULT_MAXIMUM_LIFT_COEFFICIENT,
        val carryConstraint: CarryConstraint = CarryConstraint.SCALE,
        val aerodynamics: Aerodynamics = Aerodynamics.CONSTANT_DRAG_LINEAR_LIFT,
        val spinDecayPerSecond: Double = 0.0,
    ) {
        companion object {
            private const val DEFAULT_TIME_STEP = 1.0 / 120.0
            private const val DEFAULT_OUTPUT_FRAMES_PER_SECOND = 60.0
            private const val DEFAULT_GRAVITY = 9.80665
            private const val DEFAULT_AIR_DENSITY = 1.204
            private const val DEFAULT_DRAG_COEFFICIENT = 0.24
            private const val DEFAULT_LIFT_SLOPE = 0.60
            private const val DEFAULT_MAXIMUM_LIFT_COEFFICIENT = 0.34
            private const val CONDITIONS_TIME_STEP = 0.01
            private const val BACKEND_GRAVITY = 9.81
            private const val BACKEND_SPIN_DECAY_PER_SECOND = 0.04

            /** The reference renderer's model: constant Cd 0.24, linear lift, ρ 1.204, x/z rescale. */
            val standard = Configuration()

            /**
             * Backend-equivalent air for conditions adjustments: g = 9.81,
             * [AirDensity.ISA_SEA_LEVEL] (the server's carry assumes 1.225, `ballistics.py:39`; the
             * reference renderer's 1.204 was warmer air), the Ferguson polynomial aerodynamics,
             * 0.04/s spin decay and no carry constraint.
             *
             * It steps at 100 Hz, not the backend's 500 Hz: in the backend's own simulator that moves
             * carry and the mile-high density ratio by under 0.01 % for driver, 7-iron and PW, and it
             * is five times cheaper. No frames are resampled ([outputFramesPerSecond] 0), so a
             * conditions run keeps every integration step and [BallFlightSimulator.fitToCarry] can
             * reuse it.
             */
            val conditions =
                Configuration(
                    timeStep = CONDITIONS_TIME_STEP,
                    outputFramesPerSecond = 0.0,
                    gravity = BACKEND_GRAVITY,
                    airDensity = AirDensity.ISA_SEA_LEVEL,
                    carryConstraint = CarryConstraint.NONE,
                    aerodynamics = Aerodynamics.SPIN_PARAMETER_POLYNOMIAL,
                    spinDecayPerSecond = BACKEND_SPIN_DECAY_PER_SECOND,
                )

            /**
             * Plan F2b: every drawn and measured flight. The physics of [conditions] (so a
             * conditions run is a valid k = 1 start for the fit), resampled at 60 fps and
             * drag-fitted to the target carry. Callers set [airDensity] from the conditions.
             */
            val render =
                conditions.copy(
                    outputFramesPerSecond = DEFAULT_OUTPUT_FRAMES_PER_SECOND,
                    carryConstraint = CarryConstraint.DRAG_FIT,
                )

            val vacuum =
                Configuration(
                    airDensity = 0.0,
                    dragCoefficient = 0.0,
                    liftSlope = 0.0,
                    maximumLiftCoefficient = 0.0,
                    carryConstraint = CarryConstraint.NONE,
                )
        }
    }

    /** The raw integrated flight (every step plus the interpolated landing) and its landing spin. */
    private class Run(
        val points: List<FlightPoint>,
        val landingSpinRpm: Double,
    ) {
        val carryMeters: Double get() = points.last().positionMeters.z
    }

    /** A constrained flight before resampling. */
    private class Constrained(
        val points: List<FlightPoint>,
        val landingSpinRpm: Double,
        val carryFit: CarryFit?,
    )

    private data class State(
        val position: Vec3,
        val velocity: Vec3,
    )

    private data class Derivative(
        val position: Vec3,
        val velocity: Vec3,
    )

    fun simulate(input: FlightInput): FlightTrajectory = finish(input, integrate(input, dragScale = 1.0))

    /**
     * [simulate] for an [unconstrained] flight of the same [input] that the caller already has (for
     * example [ConditionsAdjuster]'s conditions run), so the k = 1 run isn't integrated twice.
     * [unconstrained] must come from a configuration with this one's physics (time step, gravity,
     * air density, aerodynamics, spin decay) and no resampling, such as [Configuration.conditions]
     * at the same density.
     */
    fun fitToCarry(
        input: FlightInput,
        unconstrained: FlightTrajectory,
    ): FlightTrajectory {
        if (unconstrained.points.size < 2) return simulate(input)
        return finish(input, Run(unconstrained.points, unconstrained.landingSpinRpm ?: input.spinRpm))
    }

    private fun finish(
        input: FlightInput,
        run: Run,
    ): FlightTrajectory {
        val constrained = constrain(input, run)
        return FlightTrajectory(
            eventId = input.eventId,
            points = resample(constrained.points, configuration.outputFramesPerSecond),
            provenance = input.provenance,
            landingSpinRpm = constrained.landingSpinRpm,
            carryFit = constrained.carryFit,
        )
    }

    private fun constrain(
        input: FlightInput,
        run: Run,
    ): Constrained {
        val target = input.targetCarryMeters
        val constrainable = run.carryMeters > MINIMUM_CARRY_FOR_SCALING_METERS && target > 0
        return when {
            !constrainable || configuration.carryConstraint == CarryConstraint.NONE -> {
                Constrained(run.points, run.landingSpinRpm, carryFit = null)
            }

            configuration.carryConstraint == CarryConstraint.SCALE -> {
                val factor = target / run.carryMeters
                Constrained(run.points.map { scalePoint(it, factor) }, run.landingSpinRpm, CarryFit(1.0, factor))
            }

            else -> {
                dragFit(input, run)
            }
        }
    }

    /** [CarryConstraint.DRAG_FIT]: search k until the carry is within tolerance, then scale out the residual. */
    private fun dragFit(
        input: FlightInput,
        unit: Run,
    ): Constrained {
        val target = input.targetCarryMeters
        var best = unit
        var bestScale = 1.0
        var previous = unit
        var previousScale = 1.0
        var iteration = 0
        while (abs(best.carryMeters - target) > CARRY_FIT_TOLERANCE_METERS && iteration < MAXIMUM_FIT_ITERATIONS) {
            // More drag, less carry. Secant on the carry-vs-k curve (monotone and smooth); the first
            // step, and any non-decreasing slope, uses the typical relative slope instead. At a
            // clamp that still can't reach the target the next k equals this one, so the loop stops.
            val slope =
                if (best !== previous && bestScale != previousScale) {
                    (best.carryMeters - previous.carryMeters) / (bestScale - previousScale)
                } else {
                    0.0
                }
            val step =
                if (slope < 0) {
                    (target - best.carryMeters) / slope
                } else {
                    (best.carryMeters - target) / (TYPICAL_RELATIVE_CARRY_SLOPE * best.carryMeters)
                }
            val next = (bestScale + step).coerceIn(DRAG_SCALE_MIN, DRAG_SCALE_MAX)
            if (next == bestScale) break
            previous = best
            previousScale = bestScale
            best = integrate(input, next)
            bestScale = next
            iteration++
        }
        val residual = target / best.carryMeters
        val points = if (residual == 1.0) best.points else best.points.map { scalePoint(it, residual) }
        return Constrained(points, best.landingSpinRpm, CarryFit(dragScale = bestScale, residualScale = residual))
    }

    /** RK4 from the tee to the interpolated landing, with Cd × [dragScale]. */
    private fun integrate(
        input: FlightInput,
        dragScale: Double,
    ): Run {
        val vertical = input.launchAngleDegrees * DEGREES_TO_RADIANS
        val horizontal = input.horizontalLaunchDegrees * DEGREES_TO_RADIANS
        val horizontalSpeed = input.ballSpeedMetersPerSecond * cos(vertical)
        var state =
            State(
                position = Vec3.ZERO,
                velocity =
                    Vec3(
                        horizontalSpeed * sin(horizontal),
                        input.ballSpeedMetersPerSecond * sin(vertical),
                        horizontalSpeed * cos(horizontal),
                    ),
            )

        var time = 0.0
        var spinRpm = input.spinRpm
        val spinDecayPerStep = exp(-configuration.spinDecayPerSecond * configuration.timeStep)
        val integrated =
            mutableListOf(
                FlightPoint(time = 0.0, positionMeters = state.position, velocityMetersPerSecond = state.velocity),
            )

        while (time < MAXIMUM_FLIGHT_TIME_SECONDS) {
            val previous = state
            val previousTime = time
            val previousSpinRpm = spinRpm
            state = rk4(state, input, spinRpm, dragScale, configuration.timeStep)
            time += configuration.timeStep
            spinRpm *= spinDecayPerStep

            if (state.position.y <= 0 && time > configuration.timeStep * LANDING_GUARD_STEP_COUNT) {
                val denominator = previous.position.y - state.position.y
                val fraction = if (denominator > 0) previous.position.y / denominator else 1.0
                val landingTime = previousTime + configuration.timeStep * fraction
                val landingPosition = previous.position + (state.position - previous.position) * fraction
                val landingVelocity = previous.velocity + (state.velocity - previous.velocity) * fraction
                spinRpm = previousSpinRpm + (spinRpm - previousSpinRpm) * fraction
                integrated.add(
                    FlightPoint(
                        time = landingTime,
                        positionMeters = Vec3(landingPosition.x, 0.0, landingPosition.z),
                        velocityMetersPerSecond = landingVelocity,
                    ),
                )
                break
            }

            integrated.add(
                FlightPoint(time = time, positionMeters = state.position, velocityMetersPerSecond = state.velocity),
            )
        }
        return Run(integrated, spinRpm)
    }

    private fun acceleration(
        state: State,
        input: FlightInput,
        spinRpm: Double,
        dragScale: Double,
    ): Vec3 {
        val relativeVelocity = state.velocity - input.windMetersPerSecond
        val speed = relativeVelocity.length()
        if (speed <= MINIMUM_SPEED_FOR_AERODYNAMICS) {
            return Vec3(0.0, -configuration.gravity, 0.0)
        }

        val area = PI * BALL_RADIUS_METERS * BALL_RADIUS_METERS
        val aerodynamicScale = AERODYNAMIC_SCALE_FACTOR * configuration.airDensity * area / BALL_MASS_KILOGRAMS
        val spinRadiansPerSecond = spinRpm * RPM_TO_RADIANS_PER_SECOND
        val spinParameter = spinRadiansPerSecond * BALL_RADIUS_METERS / speed
        val dragCoefficient = configuration.dragCoefficientAt(spinParameter) * dragScale
        val liftCoefficient = configuration.liftCoefficientAt(spinParameter)
        val drag = relativeVelocity * (-aerodynamicScale * dragCoefficient * speed)

        val spinAxis = input.spinAxisDegrees * DEGREES_TO_RADIANS
        val angularVelocity =
            Vec3(
                -cos(spinAxis) * spinRadiansPerSecond,
                sin(spinAxis) * spinRadiansPerSecond,
                0.0,
            )
        val liftDirectionVector = angularVelocity cross relativeVelocity
        val liftDirectionLength = liftDirectionVector.length()
        val lift =
            if (liftDirectionLength > MINIMUM_LIFT_DIRECTION_LENGTH) {
                liftDirectionVector * (aerodynamicScale * liftCoefficient * speed * speed / liftDirectionLength)
            } else {
                Vec3.ZERO
            }

        return drag + lift + Vec3(0.0, -configuration.gravity, 0.0)
    }

    private fun rk4(
        state: State,
        input: FlightInput,
        spinRpm: Double,
        dragScale: Double,
        step: Double,
    ): State {
        val first = derivative(state, input, spinRpm, dragScale)
        val second = derivative(offset(state, first, step / RK4_HALF_STEP_DIVISOR), input, spinRpm, dragScale)
        val third = derivative(offset(state, second, step / RK4_HALF_STEP_DIVISOR), input, spinRpm, dragScale)
        val fourth = derivative(offset(state, third, step), input, spinRpm, dragScale)

        return State(
            position =
                state.position +
                    (
                        first.position + second.position * RK4_MIDDLE_WEIGHT +
                            third.position * RK4_MIDDLE_WEIGHT + fourth.position
                    ) * (step / RK4_WEIGHT_DIVISOR),
            velocity =
                state.velocity +
                    (
                        first.velocity + second.velocity * RK4_MIDDLE_WEIGHT +
                            third.velocity * RK4_MIDDLE_WEIGHT + fourth.velocity
                    ) * (step / RK4_WEIGHT_DIVISOR),
        )
    }

    private fun derivative(
        state: State,
        input: FlightInput,
        spinRpm: Double,
        dragScale: Double,
    ): Derivative = Derivative(position = state.velocity, velocity = acceleration(state, input, spinRpm, dragScale))

    private fun offset(
        state: State,
        derivative: Derivative,
        scale: Double,
    ): State =
        State(
            position = state.position + derivative.position * scale,
            velocity = state.velocity + derivative.velocity * scale,
        )

    companion object {
        /** The drag fit's bracket (plan F2b); a residual beyond it is closed by scaling. */
        const val DRAG_SCALE_MIN = 0.8
        const val DRAG_SCALE_MAX = 1.25

        /** The drag fit stops within 0.1 yd of the target carry. */
        const val CARRY_FIT_TOLERANCE_METERS = 0.1 * 0.9144

        private const val MAXIMUM_FIT_ITERATIONS = 20

        /**
         * −d(carry)/dk ÷ carry near k = 1: +16 % drag costs a driver about 10 % carry and −14 %
         * gains a 7-iron about 10 % (the research runs behind plan F2b), so about 0.62.
         */
        private const val TYPICAL_RELATIVE_CARRY_SLOPE = 0.62
        private const val BALL_MASS_KILOGRAMS = 0.04593
        private const val BALL_RADIUS_METERS = 0.02135
        private const val MAXIMUM_FLIGHT_TIME_SECONDS = 20.0
        private const val DEGREES_TO_RADIANS = PI / 180.0
        private const val RPM_TO_RADIANS_PER_SECOND = 2.0 * PI / 60.0
        private const val AERODYNAMIC_SCALE_FACTOR = 0.5
        private const val MINIMUM_SPEED_FOR_AERODYNAMICS = 0.01
        private const val MINIMUM_LIFT_DIRECTION_LENGTH = 0.0001
        private const val MINIMUM_CARRY_FOR_SCALING_METERS = 0.5
        private const val LANDING_GUARD_STEP_COUNT = 2
        private const val RK4_HALF_STEP_DIVISOR = 2.0
        private const val RK4_MIDDLE_WEIGHT = 2.0
        private const val RK4_WEIGHT_DIVISOR = 6.0
    }
}

/** [points] linearly resampled at [framesPerSecond], keeping the exact landing; as-is at 0. */
private fun resample(
    points: List<FlightPoint>,
    framesPerSecond: Double,
): List<FlightPoint> {
    val last = points.lastOrNull()
    if (last == null || points.size <= 1 || framesPerSecond <= 0) {
        return points
    }
    val interval = 1.0 / framesPerSecond
    val result = mutableListOf<FlightPoint>()
    var sourceIndex = 0
    var time = 0.0

    while (time < last.time) {
        while (sourceIndex + 1 < points.size && points[sourceIndex + 1].time < time) {
            sourceIndex += 1
        }
        val start = points[sourceIndex]
        val end = points[minOf(sourceIndex + 1, points.size - 1)]
        val span = end.time - start.time
        val progress = if (span > 0) (time - start.time) / span else 0.0
        result.add(
            FlightPoint(
                time = time,
                positionMeters = start.positionMeters + (end.positionMeters - start.positionMeters) * progress,
                velocityMetersPerSecond =
                    start.velocityMetersPerSecond +
                        (end.velocityMetersPerSecond - start.velocityMetersPerSecond) * progress,
            ),
        )
        time += interval
    }
    result.add(last)
    return result
}

/** [point] with its x/z position and velocity scaled by [factor] (height and vertical speed kept). */
private fun scalePoint(
    point: FlightPoint,
    factor: Double,
): FlightPoint =
    FlightPoint(
        time = point.time,
        positionMeters =
            Vec3(point.positionMeters.x * factor, point.positionMeters.y, point.positionMeters.z * factor),
        velocityMetersPerSecond =
            Vec3(
                point.velocityMetersPerSecond.x * factor,
                point.velocityMetersPerSecond.y,
                point.velocityMetersPerSecond.z * factor,
            ),
    )

/** Cd at [spinParameter] under [BallFlightSimulator.Configuration.aerodynamics]. */
private fun BallFlightSimulator.Configuration.dragCoefficientAt(spinParameter: Double): Double =
    when (aerodynamics) {
        BallFlightSimulator.Aerodynamics.CONSTANT_DRAG_LINEAR_LIFT -> {
            dragCoefficient
        }

        BallFlightSimulator.Aerodynamics.SPIN_PARAMETER_POLYNOMIAL -> {
            val bounded = minOf(spinParameter, FergusonPolynomials.SPIN_PARAMETER_MAX)
            FergusonPolynomials.CD_0 + FergusonPolynomials.CD_1 * bounded + FergusonPolynomials.CD_2 * bounded * bounded
        }
    }

/** Cl at [spinParameter] under [BallFlightSimulator.Configuration.aerodynamics]. */
private fun BallFlightSimulator.Configuration.liftCoefficientAt(spinParameter: Double): Double =
    when (aerodynamics) {
        BallFlightSimulator.Aerodynamics.CONSTANT_DRAG_LINEAR_LIFT -> {
            (liftSlope * spinParameter).coerceIn(0.0, maximumLiftCoefficient)
        }

        BallFlightSimulator.Aerodynamics.SPIN_PARAMETER_POLYNOMIAL -> {
            val bounded = minOf(spinParameter, FergusonPolynomials.SPIN_PARAMETER_MAX)
            val lift =
                FergusonPolynomials.CL_0 + FergusonPolynomials.CL_1 * bounded +
                    FergusonPolynomials.CL_2 * bounded * bounded
            if (spinParameter <= 0) 0.0 else maxOf(0.0, lift)
        }
    }

/**
 * Ferguson, McNally & McPhee (2022) Cd/Cl polynomials in the spin parameter, as in the backend's
 * `ballistics.py` CD_POLY, CL_POLY and SP_FIT_MAX at 7ca4b40.
 */
private object FergusonPolynomials {
    const val CD_0 = 0.1304
    const val CD_1 = 0.9287
    const val CD_2 = -0.8259
    const val CL_0 = 0.0504
    const val CL_1 = 1.2031
    const val CL_2 = -1.1490
    const val SPIN_PARAMETER_MAX = 0.75
}
