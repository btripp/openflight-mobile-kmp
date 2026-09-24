// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

/**
 * A camera position/target pair in scene space (y up, downrange is −z), ported from
 * `RangeCameraPlanner.swift`'s `RangeCameraPose`. [verticalFovDegrees] is the vertical field of
 * view; the reference's fixed camera uses RealityKit's 58°, and the follow camera (R7a) widens it
 * to frame the landing.
 */
data class RangeCameraPose(
    val position: Vec3,
    val target: Vec3,
    val verticalFovDegrees: Double = DEFAULT_VERTICAL_FOV_DEGREES,
) {
    companion object {
        /** `camera.camera.fieldOfViewInDegrees = 58` (RangeSceneController.swift). */
        const val DEFAULT_VERTICAL_FOV_DEGREES = 58.0
    }
}

/**
 * Produces a fixed tee-box camera aimed down the target line. The pose never depends on the
 * ball, so distance, height, and lateral curvature remain visible relative to a stable range
 * throughout the complete flight. Ported from `ios/OpenFlight/DrivingRange/RangeCameraPlanner.swift`.
 */
class RangeCameraPlanner {
    val pose =
        RangeCameraPose(
            position = Vec3(CAMERA_X, CAMERA_POSITION_Y, CAMERA_POSITION_Z),
            target = Vec3(CAMERA_X, CAMERA_TARGET_Y, CAMERA_TARGET_Z),
        )

    private companion object {
        const val CAMERA_X = 0.0
        const val CAMERA_POSITION_Y = 3.4
        const val CAMERA_POSITION_Z = 8.0
        const val CAMERA_TARGET_Y = 9.0
        const val CAMERA_TARGET_Z = -145.0
    }
}
