// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.FollowCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPose

/**
 * The one place both renderers (the Android Canvas and the iOS RealityKit scene) get the range
 * camera's pose from, for the state's [DrivingRangeUiState.cameraMode] (plan R7a). It is pure and
 * cheap: call it every frame.
 *
 * - [RangeCameraMode.FIXED], or no trajectory: the reference's tee camera, [fixedPose].
 * - [RangeCameraMode.FOLLOW]: [FollowCameraPlanner]'s pose for the playback instant.
 *
 * Kept free of default arguments and nullable numbers so it reads naturally from Swift:
 * `RangeCameraRig().pose(mode:trajectory:playbackProgress:landedElapsedSeconds:)`.
 */
class RangeCameraRig {
    private val follow = FollowCameraPlanner()

    /** The fixed tee camera. */
    val fixedPose: RangeCameraPose = RangeCameraPlanner().pose

    /** How long after landing the follow camera keeps moving; keep drawing frames until then. */
    val settleSeconds: Double = FollowCameraPlanner.SETTLE_SECONDS

    /**
     * The camera pose for one frame.
     *
     * @param trajectory the flight on screen (the active flight, or the last one while it stays
     *   frozen after landing), or `null` before the first flight.
     * @param playbackProgress 0 at launch to 1 at landing, over the playback duration
     *   ([playbackSeconds]); it maps linearly onto the flight's time.
     * @param landedElapsedSeconds seconds since the ball landed; only read once
     *   [playbackProgress] reaches 1.
     */
    fun pose(
        mode: RangeCameraMode,
        trajectory: FlightTrajectory?,
        playbackProgress: Double,
        landedElapsedSeconds: Double,
    ): RangeCameraPose =
        when {
            mode == RangeCameraMode.FIXED || trajectory == null -> {
                fixedPose
            }

            playbackProgress >= 1.0 -> {
                follow.landedPose(trajectory, landedElapsedSeconds.coerceAtLeast(0.0))
            }

            else -> {
                follow.flightPose(trajectory, playbackProgress.coerceAtLeast(0.0) * trajectory.flightTime)
            }
        }
}
