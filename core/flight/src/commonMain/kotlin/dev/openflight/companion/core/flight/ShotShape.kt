// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import kotlin.math.PI
import kotlin.math.tan

/** Where the ball started relative to the target line, for a right-handed golfer. */
enum class StartLine {
    PULL,
    STRAIGHT,
    PUSH,
}

/** Which way, and how hard, the ball curved off its start line, for a right-handed golfer. */
enum class Curve {
    HOOK,
    DRAW,
    STRAIGHT,
    FADE,
    SLICE,
}

/**
 * A shot's shape: its start line and its curve, as golfers name them ("Push fade", "Pull hook",
 * "Draw"). Either part is `null` when the measurement behind it (horizontal launch, spin axis) was
 * missing and the flight drew the default, so the UI never names a shape nobody measured.
 *
 * The app has no handedness setting, so a right-handed golfer is assumed: a curve to the left is a
 * draw or hook and one to the right a fade or slice.
 */
data class ShotShape(
    val start: StartLine?,
    val curve: Curve?,
) {
    /**
     * "Push fade", "Draw", "Straight", "Pull"; `null` when neither part was measured, or when only
     * an on-target start was (calling that "Straight" would claim a curve nobody measured).
     */
    val label: String?
        get() {
            val startWord =
                when (start) {
                    StartLine.PULL -> "Pull"
                    StartLine.PUSH -> "Push"
                    StartLine.STRAIGHT, null -> null
                }
            val curveWord =
                when (curve) {
                    Curve.HOOK -> "hook"
                    Curve.DRAW -> "draw"
                    Curve.FADE -> "fade"
                    Curve.SLICE -> "slice"
                    Curve.STRAIGHT, null -> null
                }
            return when {
                curve == null && startWord == null -> null
                startWord != null && curveWord != null -> "$startWord $curveWord"
                startWord != null -> startWord
                curveWord != null -> curveWord.replaceFirstChar { it.uppercaseChar() }
                else -> "Straight"
            }
        }

    /** One plain sentence on what the ball did, e.g. "Started right of target, curved right." */
    val description: String?
        get() {
            val startPart =
                when (start) {
                    StartLine.PULL -> "Started left of target"
                    StartLine.PUSH -> "Started right of target"
                    StartLine.STRAIGHT -> "Started on target"
                    null -> null
                }
            val curvePart =
                when (curve) {
                    Curve.HOOK -> "curved hard left"
                    Curve.DRAW -> "curved left"
                    Curve.STRAIGHT -> "held its line"
                    Curve.FADE -> "curved right"
                    Curve.SLICE -> "curved hard right"
                    null -> null
                }
            return when {
                startPart != null && curvePart != null -> "$startPart, $curvePart."
                startPart != null -> "$startPart."
                curvePart != null -> curvePart.replaceFirstChar { it.uppercaseChar() } + "."
                else -> null
            }
        }

    companion object {
        /**
         * Classifies [trajectory], flown from [input]. The start line is [FlightInput.horizontalLaunchDegrees];
         * the curve is how far the ball landed from where its start line would have put it, as a
         * fraction of the carry, so the name always matches the flight the app draws, whatever the
         * club. That flight includes any crosswind it was drawn in (only once a target bearing is
         * set), so a strong crosswind can read as curve.
         */
        fun classify(
            input: FlightInput,
            trajectory: FlightTrajectory,
        ): ShotShape {
            val estimated = input.provenance.estimatedParameters
            val start =
                if (FlightParameter.HORIZONTAL_LAUNCH in estimated) null else startLine(input.horizontalLaunchDegrees)
            val curve =
                if (FlightParameter.SPIN_AXIS in estimated) null else curve(input.horizontalLaunchDegrees, trajectory)
            return ShotShape(start, curve)
        }

        /** The start line for a horizontal launch angle (positive right). */
        fun startLine(horizontalLaunchDegrees: Double): StartLine =
            when {
                horizontalLaunchDegrees <= -STRAIGHT_START_DEGREES -> StartLine.PULL
                horizontalLaunchDegrees >= STRAIGHT_START_DEGREES -> StartLine.PUSH
                else -> StartLine.STRAIGHT
            }

        /** The curve for a landing [curveFraction] off the start line (positive right) per unit of carry. */
        fun curve(curveFraction: Double): Curve =
            when {
                curveFraction <= -HOOK_SLICE_FRACTION -> Curve.HOOK
                curveFraction <= -STRAIGHT_CURVE_FRACTION -> Curve.DRAW
                curveFraction >= HOOK_SLICE_FRACTION -> Curve.SLICE
                curveFraction >= STRAIGHT_CURVE_FRACTION -> Curve.FADE
                else -> Curve.STRAIGHT
            }

        private fun curve(
            horizontalLaunchDegrees: Double,
            trajectory: FlightTrajectory,
        ): Curve? {
            val landing =
                trajectory.points
                    .lastOrNull()
                    ?.positionMeters
                    ?.takeIf { it.z > 0.0 } ?: return null
            val startLineX = landing.z * tan(horizontalLaunchDegrees * DEGREES_TO_RADIANS)
            return ((landing.x - startLineX) / landing.z).takeIf { it.isFinite() }?.let(::curve)
        }

        /**
         * App-defined UI thresholds, not measured physics: a start within ±2° reads as on target; a
         * curve under 2.5 % of the carry (about 4 yd on a 165 yd 7-iron) as straight, and one past 8 %
         * (about 13 yd on that 7-iron, 21 yd on a 260 yd drive) as a hook or slice.
         */
        const val STRAIGHT_START_DEGREES = 2.0
        const val STRAIGHT_CURVE_FRACTION = 0.025
        const val HOOK_SLICE_FRACTION = 0.08

        private const val DEGREES_TO_RADIANS = PI / 180.0
    }
}
