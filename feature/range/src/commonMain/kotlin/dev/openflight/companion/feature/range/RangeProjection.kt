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
 *
 * The follow camera (plan R7a) moves every frame, so a renderer keeps one projection and calls
 * [update] per frame; nothing here allocates except [project]'s returned [ScreenPoint] (use
 * [projectInto] on hot paths).
 */
@Suppress("TooManyFunctions") // Small, allocation-free projection primitives.
class RangeProjection(
    pose: RangeCameraPose,
    width: Float,
    height: Float,
    verticalFovDegrees: Double = pose.verticalFovDegrees,
) {
    var width: Float = width
        private set
    var height: Float = height
        private set

    private var originX = 0.0
    private var originY = 0.0
    private var originZ = 0.0
    private var forwardX = 0.0
    private var forwardY = 0.0
    private var forwardZ = -1.0
    private var rightX = 1.0
    private var rightY = 0.0
    private var rightZ = 0.0
    private var upX = 0.0
    private var upY = 1.0
    private var upZ = 0.0
    private var centerX = 0.0
    private var centerY = 0.0

    /** Pixels per unit of (camera-space offset / depth). */
    var focalLengthPixels: Double = 0.0
        private set

    init {
        update(pose, width, height, verticalFovDegrees)
    }

    /** Re-aims this projection without allocating: a new camera pose and/or canvas size. */
    fun update(
        pose: RangeCameraPose,
        width: Float,
        height: Float,
        verticalFovDegrees: Double = pose.verticalFovDegrees,
    ) {
        this.width = width
        this.height = height
        originX = pose.position.x
        originY = pose.position.y
        originZ = pose.position.z

        val dx = pose.target.x - originX
        val dy = pose.target.y - originY
        val dz = pose.target.z - originZ
        val forwardLength = sqrt(dx * dx + dy * dy + dz * dz)
        forwardX = dx / forwardLength
        forwardY = dy / forwardLength
        forwardZ = dz / forwardLength

        // right = forward × worldUp, up = right × forward.
        val rightLength = sqrt(forwardZ * forwardZ + forwardX * forwardX)
        rightX = -forwardZ / rightLength
        rightY = 0.0
        rightZ = forwardX / rightLength
        upX = rightY * forwardZ - rightZ * forwardY
        upY = rightZ * forwardX - rightX * forwardZ
        upZ = rightX * forwardY - rightY * forwardX

        focalLengthPixels = (height / HALF) / tan(verticalFovDegrees * PI / DEGREES_PER_HALF_TURN / HALF)
        centerX = width / HALF
        centerY = height / HALF
    }

    /** Distance in front of the camera along its view axis; ≤ [NEAR_PLANE_METERS] is not drawable. */
    fun depth(
        x: Double,
        y: Double,
        z: Double,
    ): Double = (x - originX) * forwardX + (y - originY) * forwardY + (z - originZ) * forwardZ

    /** The canvas point for a scene point, or [ScreenPoint.Unspecified] when it is behind the near plane. */
    fun project(
        x: Double,
        y: Double,
        z: Double,
    ): ScreenPoint {
        val depth = depth(x, y, z)
        if (depth <= NEAR_PLANE_METERS) return ScreenPoint.Unspecified
        return ScreenPoint(screenX(x, y, z, depth), screenY(x, y, z, depth))
    }

    fun project(point: Vec3): ScreenPoint = project(point.x, point.y, point.z)

    /**
     * [project] without allocating: writes x and y to [out] at [index] and [index] + 1 (NaN when
     * the point is behind the near plane) and returns whether the point is drawable.
     */
    fun projectInto(
        x: Double,
        y: Double,
        z: Double,
        out: FloatArray,
        index: Int,
    ): Boolean {
        val depth = depth(x, y, z)
        if (depth <= NEAR_PLANE_METERS) {
            out[index] = Float.NaN
            out[index + 1] = Float.NaN
            return false
        }
        out[index] = screenX(x, y, z, depth)
        out[index + 1] = screenY(x, y, z, depth)
        return true
    }

    private fun screenX(
        x: Double,
        y: Double,
        z: Double,
        depth: Double,
    ): Float {
        val cameraX = (x - originX) * rightX + (y - originY) * rightY + (z - originZ) * rightZ
        return (centerX + focalLengthPixels * cameraX / depth).toFloat()
    }

    private fun screenY(
        x: Double,
        y: Double,
        z: Double,
        depth: Double,
    ): Float {
        val cameraY = (x - originX) * upX + (y - originY) * upY + (z - originZ) * upZ
        return (centerY - focalLengthPixels * cameraY / depth).toFloat()
    }

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
            originX + rightX * cameraX + upX * cameraY + forwardX * depth,
            originY + rightY * cameraX + upY * cameraY + forwardY * depth,
            originZ + rightZ * cameraX + upZ * cameraY + forwardZ * depth,
        )

    fun cameraX(point: Vec3): Double =
        (point.x - originX) * rightX + (point.y - originY) * rightY + (point.z - originZ) * rightZ

    fun cameraY(point: Vec3): Double = (point.x - originX) * upX + (point.y - originY) * upY + (point.z - originZ) * upZ

    /**
     * The screen y of the horizon: a ground point very far away straight ahead of the camera
     * (horizontally). Above the canvas when the camera looks steeply down.
     */
    fun horizonY(): Float {
        val length = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (length <
            MIN_HORIZONTAL_LENGTH
        ) {
            return if (forwardY < 0) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
        }
        val x = originX + forwardX / length * HORIZON_DISTANCE_METERS
        val z = originZ + forwardZ / length * HORIZON_DISTANCE_METERS
        val depth = depth(x, 0.0, z)
        return if (depth <= NEAR_PLANE_METERS) Float.NEGATIVE_INFINITY else screenY(x, 0.0, z, depth)
    }

    companion object {
        /** `camera.camera.fieldOfViewInDegrees = 58` (RangeSceneController.swift). */
        const val CAMERA_VERTICAL_FOV_DEGREES = RangeCameraPose.DEFAULT_VERTICAL_FOV_DEGREES
        const val NEAR_PLANE_METERS = 0.1

        /** The ball's radius in the scene; the reference lifts every flight point by it. */
        const val BALL_RADIUS_METERS = 0.18

        private const val HORIZON_DISTANCE_METERS = 100_000.0
        private const val MIN_HORIZONTAL_LENGTH = 1e-9
        private const val HALF = 2.0
        private const val DEGREES_PER_HALF_TURN = 180.0

        /** Simulator space (+z downrange) to scene space (−z downrange), as `scenePosition(_:)` does. */
        fun flightToScene(position: Vec3): Vec3 = Vec3(position.x, position.y + BALL_RADIUS_METERS, -position.z)
    }
}

/** Seconds the scene takes to fly [trajectory]: 0.9 s under reduced motion (RangeSceneController.swift). */
fun playbackSeconds(
    trajectory: FlightTrajectory,
    reduceMotion: Boolean,
): Double = if (reduceMotion) REDUCED_MOTION_PLAYBACK_SECONDS else trajectory.playbackDuration

const val REDUCED_MOTION_PLAYBACK_SECONDS = 0.9
