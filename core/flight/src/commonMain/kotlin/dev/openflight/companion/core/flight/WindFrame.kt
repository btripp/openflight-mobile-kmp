// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * This wind as the simulator's air velocity ([FlightInput.windMetersPerSecond]) for a golfer
 * hitting toward [target]: x = lateral (+ right of the target line), y = 0, z = downrange.
 *
 * [Wind.fromDegrees] is where the wind blows **from**, so the air moves toward
 * `fromDegrees + 180`. Measured clockwise from the target line, that heading is
 * `θ = fromDegrees + 180 − target`, giving `z = speed·cos θ` (tailwind positive) and
 * `x = speed·sin θ` (a wind from the left pushes the ball right). A wind from the target
 * direction is a pure headwind.
 *
 * The speed is used as reported (weather services give it at 10 m); no height profile is applied.
 */
fun Wind.toSimulatorFrame(target: TargetBearing): Vec3 {
    if (isCalm) return Vec3.ZERO
    val heading = (fromDegrees + HALF_TURN_DEGREES - target.degrees) * DEGREES_TO_RADIANS
    return Vec3(speedMps * sin(heading), 0.0, speedMps * cos(heading))
}

private const val HALF_TURN_DEGREES = 180.0
private const val DEGREES_TO_RADIANS = PI / 180.0
