// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * RK4 ball-flight integrator, ported from `ios/OpenFlight/DrivingRange/BallFlightSimulator.swift`.
 * Every physical constant below is copied verbatim from the reference.
 */
class BallFlightSimulator(
    private val configuration: Configuration = Configuration.standard,
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
     * Tunable physics knobs, ported from `BallFlightSimulator.Configuration`. [standard] mirrors
     * OpenFlight air; [vacuum] strips out drag and lift, used as an independent closed-form
     * oracle in tests. [conditions] is the backend-equivalent air the conditions adjustment uses
     * (plan F2); the renderer keeps [standard].
     *
     * [spinDecayPerSecond] is an exponential spin decay, ω(t) = ω₀·e^(−rate·t), applied between
     * integration steps as the backend does (about 4 %/s, Kiratidis & Leinweber 2018). It is 0
     * (off) by default, so the renderer's flight is unchanged.
     */
    data class Configuration(
        val timeStep: Double = DEFAULT_TIME_STEP,
        val outputFramesPerSecond: Double = DEFAULT_OUTPUT_FRAMES_PER_SECOND,
        val gravity: Double = DEFAULT_GRAVITY,
        val airDensity: Double = DEFAULT_AIR_DENSITY,
        val dragCoefficient: Double = DEFAULT_DRAG_COEFFICIENT,
        val liftSlope: Double = DEFAULT_LIFT_SLOPE,
        val maximumLiftCoefficient: Double = DEFAULT_MAXIMUM_LIFT_COEFFICIENT,
        val constrainToTargetCarry: Boolean = true,
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

            val standard = Configuration()

            /**
             * Backend-equivalent air for conditions adjustments: g = 9.81,
             * [AirDensity.ISA_SEA_LEVEL] (the server's carry assumes 1.225, `ballistics.py:39`;
             * the renderer's 1.204 is the reference app's warmer air), the Ferguson polynomial
             * aerodynamics, 0.04/s spin decay and no rescaling to the server's carry.
             *
             * It steps at 100 Hz, not the backend's 500 Hz: in the backend's own simulator that
             * moves carry and the mile-high density ratio by under 0.01 % for driver, 7-iron and
             * PW, and it is five times cheaper (two runs per shot, on phones). No frames are
             * resampled ([outputFramesPerSecond] 0); callers only read the landing.
             */
            val conditions =
                Configuration(
                    timeStep = CONDITIONS_TIME_STEP,
                    outputFramesPerSecond = 0.0,
                    gravity = BACKEND_GRAVITY,
                    airDensity = AirDensity.ISA_SEA_LEVEL,
                    constrainToTargetCarry = false,
                    aerodynamics = Aerodynamics.SPIN_PARAMETER_POLYNOMIAL,
                    spinDecayPerSecond = BACKEND_SPIN_DECAY_PER_SECOND,
                )

            val vacuum =
                Configuration(
                    airDensity = 0.0,
                    dragCoefficient = 0.0,
                    liftSlope = 0.0,
                    maximumLiftCoefficient = 0.0,
                    constrainToTargetCarry = false,
                )
        }
    }

    private data class State(
        val position: Vec3,
        val velocity: Vec3,
    )

    private data class Derivative(
        val position: Vec3,
        val velocity: Vec3,
    )

    fun simulate(input: FlightInput): FlightTrajectory {
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
            state = rk4(state, input, spinRpm, configuration.timeStep)
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

        val constrained = constrain(integrated, input.targetCarryMeters)
        val compact = resample(constrained)
        return FlightTrajectory(
            eventId = input.eventId,
            points = compact,
            provenance = input.provenance,
            landingSpinRpm = spinRpm,
        )
    }

    private fun acceleration(
        state: State,
        input: FlightInput,
        spinRpm: Double,
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
        val dragCoefficient = configuration.dragCoefficientAt(spinParameter)
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
        step: Double,
    ): State {
        val first = derivative(state, input, spinRpm)
        val second = derivative(offset(state, first, step / RK4_HALF_STEP_DIVISOR), input, spinRpm)
        val third = derivative(offset(state, second, step / RK4_HALF_STEP_DIVISOR), input, spinRpm)
        val fourth = derivative(offset(state, third, step), input, spinRpm)

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
    ): Derivative = Derivative(position = state.velocity, velocity = acceleration(state, input, spinRpm))

    private fun offset(
        state: State,
        derivative: Derivative,
        scale: Double,
    ): State =
        State(
            position = state.position + derivative.position * scale,
            velocity = state.velocity + derivative.velocity * scale,
        )

    private fun constrain(
        points: List<FlightPoint>,
        targetCarry: Double,
    ): List<FlightPoint> {
        val rawCarry = points.lastOrNull()?.positionMeters?.z ?: return points
        return if (canConstrainCarry(rawCarry, targetCarry)) {
            val scaleFactor = targetCarry / rawCarry
            points.map { point -> scalePoint(point, scaleFactor) }
        } else {
            points
        }
    }

    private fun canConstrainCarry(
        rawCarry: Double,
        targetCarry: Double,
    ): Boolean = configuration.constrainToTargetCarry && rawCarry > MINIMUM_CARRY_FOR_SCALING_METERS && targetCarry > 0

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

    private fun resample(points: List<FlightPoint>): List<FlightPoint> {
        val last = points.lastOrNull()
        if (last == null || points.size <= 1 || configuration.outputFramesPerSecond <= 0) {
            return points
        }
        val interval = 1.0 / configuration.outputFramesPerSecond
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

    private companion object {
        const val BALL_MASS_KILOGRAMS = 0.04593
        const val BALL_RADIUS_METERS = 0.02135
        const val MAXIMUM_FLIGHT_TIME_SECONDS = 20.0
        const val DEGREES_TO_RADIANS = PI / 180.0
        const val RPM_TO_RADIANS_PER_SECOND = 2.0 * PI / 60.0
        const val AERODYNAMIC_SCALE_FACTOR = 0.5
        const val MINIMUM_SPEED_FOR_AERODYNAMICS = 0.01
        const val MINIMUM_LIFT_DIRECTION_LENGTH = 0.0001
        const val MINIMUM_CARRY_FOR_SCALING_METERS = 0.5
        const val LANDING_GUARD_STEP_COUNT = 2
        const val RK4_HALF_STEP_DIVISOR = 2.0
        const val RK4_MIDDLE_WEIGHT = 2.0
        const val RK4_WEIGHT_DIVISOR = 6.0
    }
}

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
