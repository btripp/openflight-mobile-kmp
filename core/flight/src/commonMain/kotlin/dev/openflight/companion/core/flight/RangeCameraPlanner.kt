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
 *
 * Plan F8a2p moves it away from the reference's eye-level camera. That camera was 3.4 m up and 8 m
 * behind the tee, looking slightly *up* through 58°, which put the horizon just below the middle
 * of a phone and squeezed the landing zone into a strip about 1 % of the screen tall. The camera
 * now stands [CAMERA_POSITION_Z] m behind the tee and [CAMERA_POSITION_Y] m up, and looks down
 * through a narrower [FIELD_OF_VIEW_DEGREES]° onto the ground [TARGET_DOWNRANGE_METERS] m
 * downrange. The horizon falls [HORIZON_FRACTION] of the way down the canvas at any aspect (the
 * field of view is vertical). The tee box sits about four fifths of the way down, and the 50–250 yd
 * markers spread over about an eighth of the screen. A driver's apex still stays in frame.
 */
class RangeCameraPlanner {
    val pose =
        RangeCameraPose(
            position = Vec3(CAMERA_X, CAMERA_POSITION_Y, CAMERA_POSITION_Z),
            target = Vec3(CAMERA_X, 0.0, -TARGET_DOWNRANGE_METERS),
            verticalFovDegrees = FIELD_OF_VIEW_DEGREES,
        )

    companion object {
        /** Where the tee camera's horizon falls, as a fraction of the canvas height from the top. */
        const val HORIZON_FRACTION = 0.4

        /** The camera's distance behind the tee, metres (scene +z). */
        const val CAMERA_POSITION_Z = 36.0

        /** The pivot the camera looks at (and the user's orbit swings about), metres downrange of the tee. */
        const val TARGET_DOWNRANGE_METERS = 108.8528

        private const val CAMERA_X = 0.0
        private const val CAMERA_POSITION_Y = 12.0
        private const val FIELD_OF_VIEW_DEGREES = 45.0
    }
}
