// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import dev.openflight.companion.core.model.METERS_PER_YARD
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A planned shot reduced to what the shot detail's mini flight view draws: the flight in yards
 * (x right of the target line, y up, z downrange), sampled evenly in time so playback is just an
 * index, plus where the estimated roll leaves the ball.
 *
 * @property points at least two samples from the tee (`points.first()` is the origin) to the
 *   landing, evenly spaced in flight time.
 * @property rest where the ball stops after the estimated roll along its landing heading; the
 *   landing point when there's no roll.
 * @property descentDegrees the angle the ball comes down at, from the ground.
 */
data class ShotFlightPath(
    val points: List<Vec3>,
    val rest: Vec3,
    val flightTimeSeconds: Double,
    val apexYards: Double,
    val carryYards: Double,
    val descentDegrees: Double,
    val shape: ShotShape,
) {
    val landing: Vec3 get() = points.last()

    companion object {
        /** The default sample count: smooth on a phone-sized card, cheap to project every frame. */
        const val DEFAULT_SAMPLES = 64

        /**
         * [planned]'s flight, or `null` when its trajectory is empty. [samples] is clamped to at
         * least 2.
         */
        fun from(
            planned: PlannedShot,
            samples: Int = DEFAULT_SAMPLES,
        ): ShotFlightPath? {
            val trajectory = planned.trajectory
            val last = trajectory.points.lastOrNull() ?: return null
            val count = samples.coerceAtLeast(2)
            val duration = trajectory.flightTime
            val points =
                List(count) { index ->
                    val time = duration * index / (count - 1)
                    (trajectory.point(time)?.positionMeters ?: Vec3.ZERO) / METERS_PER_YARD
                }
            val landing = points.last()
            val velocity = last.velocityMetersPerSecond
            val groundSpeed = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
            val roll = planned.estimate.rollYards.coerceAtLeast(0.0)
            val rest =
                if (groundSpeed > 0.0) {
                    landing + Vec3(velocity.x / groundSpeed, 0.0, velocity.z / groundSpeed) * roll
                } else {
                    landing
                }
            return ShotFlightPath(
                points = points,
                rest = Vec3(rest.x, 0.0, rest.z),
                flightTimeSeconds = duration,
                apexYards = trajectory.apexMeters / METERS_PER_YARD,
                carryYards = landing.z,
                descentDegrees = atan2(-velocity.y, groundSpeed) * RADIANS_TO_DEGREES,
                shape = ShotShape.classify(planned.input, trajectory),
            )
        }

        private const val RADIANS_TO_DEGREES = 180.0 / PI
    }
}
