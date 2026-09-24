// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A pinhole camera that maps range-scene points to canvas pixels: the 2.5D stand-in for the
 * reference's RealityKit `PerspectiveCamera` (RangeSceneController.swift).
 *
 * **Axes.** Scene space is the reference's RealityKit space: y is up, x is to the right when
 * looking downrange, and downrange is **−z** (the camera sits at z = +8 and looks at z = −145).
 * The simulator's downrange axis is **+z**, so flight points go through [flightToScene] first,
 * exactly like the reference's `scenePosition(_:)`.
 *
 * The camera looks from [RangeCameraPose.position] at [RangeCameraPose.target] with world-up +y,
 * and [verticalFovDegrees] spans the canvas height (RealityKit's default orientation).
 */
class RangeProjection(
    pose: RangeCameraPose,
    val width: Float,
    val height: Float,
    verticalFovDegrees: Double = CAMERA_VERTICAL_FOV_DEGREES,
) {
    private val originX = pose.position.x
    private val originY = pose.position.y
    private val originZ = pose.position.z

    private val forward = normalized(pose.target - pose.position)

    // right = forward × worldUp, up = right × forward.
    private val right = normalized(Vec3(-forward.z, 0.0, forward.x))
    private val up = right cross forward

    /** Pixels per unit of (camera-space offset / depth). */
    val focalLengthPixels: Double = (height / 2.0) / tan(verticalFovDegrees * PI / 360.0)

    private val centerX = width / 2.0
    private val centerY = height / 2.0

    /** Distance in front of the camera along its view axis; ≤ [NEAR_PLANE_METERS] is not drawable. */
    fun depth(
        x: Double,
        y: Double,
        z: Double,
    ): Double = (x - originX) * forward.x + (y - originY) * forward.y + (z - originZ) * forward.z

    /** The canvas point for a scene point, or [ScreenPoint.Unspecified] when it is behind the near plane. */
    fun project(
        x: Double,
        y: Double,
        z: Double,
    ): ScreenPoint {
        val dx = x - originX
        val dy = y - originY
        val dz = z - originZ
        val depth = dx * forward.x + dy * forward.y + dz * forward.z
        if (depth <= NEAR_PLANE_METERS) return ScreenPoint.Unspecified
        val cameraX = dx * right.x + dy * right.y + dz * right.z
        val cameraY = dx * up.x + dy * up.y + dz * up.z
        return ScreenPoint(
            x = (centerX + focalLengthPixels * cameraX / depth).toFloat(),
            y = (centerY - focalLengthPixels * cameraY / depth).toFloat(),
        )
    }

    fun project(point: Vec3): ScreenPoint = project(point.x, point.y, point.z)

    /** The on-screen size of a [meters]-long object facing the camera at [depth]. */
    fun pixels(
        meters: Double,
        depth: Double,
    ): Float = (focalLengthPixels * meters / depth).toFloat()

    /** A camera-space point (x right, y up, z depth) back in scene space; used to clip polygons at the near plane. */
    fun sceneFromCamera(
        cameraX: Double,
        cameraY: Double,
        depth: Double,
    ): Vec3 =
        Vec3(
            originX + right.x * cameraX + up.x * cameraY + forward.x * depth,
            originY + right.y * cameraX + up.y * cameraY + forward.y * depth,
            originZ + right.z * cameraX + up.z * cameraY + forward.z * depth,
        )

    fun cameraX(point: Vec3): Double =
        (point.x - originX) * right.x + (point.y - originY) * right.y + (point.z - originZ) * right.z

    fun cameraY(point: Vec3): Double =
        (point.x - originX) * up.x + (point.y - originY) * up.y + (point.z - originZ) * up.z

    companion object {
        /** `camera.camera.fieldOfViewInDegrees = 58` (RangeSceneController.swift). */
        const val CAMERA_VERTICAL_FOV_DEGREES = 58.0
        const val NEAR_PLANE_METERS = 0.1

        /** The ball's radius in the scene; the reference lifts every flight point by it. */
        const val BALL_RADIUS_METERS = 0.18

        /** Simulator space (+z downrange) to scene space (−z downrange), as `scenePosition(_:)` does. */
        fun flightToScene(position: Vec3): Vec3 = Vec3(position.x, position.y + BALL_RADIUS_METERS, -position.z)

        private fun normalized(vector: Vec3): Vec3 {
            val length = sqrt(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z)
            return vector / length
        }
    }
}

/** Seconds the scene takes to fly [trajectory]: 0.9 s under reduced motion (RangeSceneController.swift). */
fun playbackSeconds(
    trajectory: FlightTrajectory,
    reduceMotion: Boolean,
): Double = if (reduceMotion) REDUCED_MOTION_PLAYBACK_SECONDS else trajectory.playbackDuration

const val REDUCED_MOTION_PLAYBACK_SECONDS = 0.9
