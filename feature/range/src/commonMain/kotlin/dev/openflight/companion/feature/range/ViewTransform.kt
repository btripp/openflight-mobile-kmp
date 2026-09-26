// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the pivot of a [ViewTransform] may be panned to, in scene metres (y up, downrange is −z),
 * as offsets from the base camera's target.
 */
data class PanBounds(
    val minX: Double,
    val maxX: Double,
    val minZ: Double,
    val maxZ: Double,
) {
    companion object {
        /**
         * The standard range: the fixed camera looks at a pivot 145 m downrange
         * ([dev.openflight.companion.core.flight.RangeCameraPlanner]); the ground is 180 m wide
         * and the range 390 m deep ([dev.openflight.companion.core.flight.RangeSceneDescription]).
         * The pivot may move across the ground's width and from the tee to the far end.
         */
        val STANDARD: PanBounds =
            PanBounds(
                minX = -GROUND_HALF_WIDTH_METERS,
                maxX = GROUND_HALF_WIDTH_METERS,
                minZ = -(RANGE_DEPTH_METERS - PIVOT_DOWNRANGE_METERS),
                maxZ = PIVOT_DOWNRANGE_METERS,
            )

        private const val GROUND_HALF_WIDTH_METERS = 90.0
        private const val RANGE_DEPTH_METERS = 390.0
        private const val PIVOT_DOWNRANGE_METERS = 145.0
    }
}

/**
 * The user's view of the range (plan F8a): pinch [zoom], a two-finger pan of the pivot
 * ([panX], [panZ]) and a drag orbit ([orbitYawDegrees]) around it. Pure and platform-neutral, so
 * the Android Canvas and the iOS RealityKit camera apply the same math. Every operation clamps:
 * zoom to [MIN_ZOOM]–[MAX_ZOOM], pan to [PanBounds], orbit to ±[MAX_ORBIT_DEGREES].
 *
 * [applyTo] turns a base pose (the fixed tee camera) into the transformed one:
 * - zoom narrows (or widens) the vertical field of view, so the camera doesn't move through the
 *   scene and the horizon stays put;
 * - orbit swings the camera about the vertical axis through the base target (positive yaw moves
 *   the camera to the right of the target line, looking back across it);
 * - pan translates camera and target together along the ground.
 *
 * While the transform isn't [IDENTITY], the R7 follow camera is suspended; [IDENTITY] (reset)
 * resumes it.
 */
data class ViewTransform(
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panZ: Double = 0.0,
    val orbitYawDegrees: Double = 0.0,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    fun zoomedBy(factor: Double): ViewTransform =
        if (!factor.isFinite() || factor <= 0.0) this else copy(zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM))

    fun pannedBy(
        deltaX: Double,
        deltaZ: Double,
        bounds: PanBounds = PanBounds.STANDARD,
    ): ViewTransform =
        copy(
            panX = (panX + deltaX.finiteOrZero()).coerceIn(bounds.minX, bounds.maxX),
            panZ = (panZ + deltaZ.finiteOrZero()).coerceIn(bounds.minZ, bounds.maxZ),
        )

    fun orbitedBy(deltaDegrees: Double): ViewTransform =
        copy(
            orbitYawDegrees =
                (orbitYawDegrees + deltaDegrees.finiteOrZero()).coerceIn(-MAX_ORBIT_DEGREES, MAX_ORBIT_DEGREES),
        )

    /**
     * Pans so the ground point under screen point ([fromX], [fromY]) ends up under ([toX], [toY]),
     * the "grab the ground" feel of a map. [projection] must be the camera the gesture started in
     * (this transform applied). A point above the horizon has no ground under it: no change.
     */
    @Suppress("LongParameterList") // Two screen points plus the camera and bounds.
    fun pannedAlongGround(
        projection: RangeProjection,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        bounds: PanBounds = PanBounds.STANDARD,
    ): ViewTransform {
        val from = projection.unprojectToGround(fromX, fromY)
        val to = projection.unprojectToGround(toX, toY)
        return if (from == null || to == null) this else pannedBy(from.x - to.x, from.z - to.z, bounds)
    }

    /** [base] with this transform applied. [IDENTITY] returns [base] itself. */
    fun applyTo(base: RangeCameraPose): RangeCameraPose {
        if (isIdentity) return base
        val yaw = orbitYawDegrees * PI / DEGREES_PER_HALF_TURN
        val offsetX = base.position.x - base.target.x
        val offsetZ = base.position.z - base.target.z
        // Rotation about +y seen from above; +yaw swings a camera behind the tee (+z) toward +x.
        val orbitX = offsetX * cos(yaw) + offsetZ * sin(yaw)
        val orbitZ = -offsetX * sin(yaw) + offsetZ * cos(yaw)
        val pan = Vec3(panX, 0.0, panZ)
        return RangeCameraPose(
            position = Vec3(base.target.x + orbitX, base.position.y, base.target.z + orbitZ) + pan,
            target = base.target + pan,
            verticalFovDegrees = zoomedFieldOfView(base.verticalFovDegrees, zoom),
        )
    }

    companion object {
        val IDENTITY: ViewTransform = ViewTransform()

        const val MIN_ZOOM = 0.5
        const val MAX_ZOOM = 4.0
        const val MAX_ORBIT_DEGREES = 60.0

        /** A drag across the scene's full width orbits this far. */
        const val ORBIT_DEGREES_PER_WIDTH = 90.0

        private const val DEGREES_PER_HALF_TURN = 180.0
        private const val HALF = 2.0

        /** The vertical field of view that magnifies [baseDegrees] by [zoom] at the screen centre. */
        fun zoomedFieldOfView(
            baseDegrees: Double,
            zoom: Double,
        ): Double {
            val halfBase = baseDegrees * PI / DEGREES_PER_HALF_TURN / HALF
            return atan(tan(halfBase) / zoom) * HALF * DEGREES_PER_HALF_TURN / PI
        }
    }
}

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
