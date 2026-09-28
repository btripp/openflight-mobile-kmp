// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
         * The standard range: the fixed camera looks at a pivot about 109 m downrange
         * ([RangeCameraPlanner.TARGET_DOWNRANGE_METERS]); the ground is 180 m wide
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
        private const val PIVOT_DOWNRANGE_METERS = RangeCameraPlanner.TARGET_DOWNRANGE_METERS
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
 * resumes it. A new live shot keeps the transform (the user moved the view on purpose; the
 * "Reset view" chip and a double tap bring the tee or follow camera back).
 *
 * Plan F8a2p's map-like gestures, the same on both platforms (all in canvas pixels):
 * - one finger drags the range ([draggedAlongGround]): the ground under the finger follows it;
 * - a pinch zooms about its centre ([zoomedAbout]); zoom is pinch-only on screen, and the
 *   screen readers' zoom actions step it ([zoomedBySteps]);
 * - two fingers twisting, or dragging sideways, orbit ([orbitedBy]);
 * - a double tap, or the "Reset view" chip, resets to [IDENTITY]; a tap (no drag) still selects an
 *   overlay landing.
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
        if (from == null || to == null) return this
        // Plan F8a2p: near the horizon a pixel spans tens of metres, so one step is capped.
        var deltaX = from.x - to.x
        var deltaZ = from.z - to.z
        val length = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        if (length > MAX_PAN_STEP_METERS) {
            deltaX *= MAX_PAN_STEP_METERS / length
            deltaZ *= MAX_PAN_STEP_METERS / length
        }
        return pannedBy(deltaX, deltaZ, bounds)
    }

    /**
     * Plan F8a2p, the map-like one-finger drag: [pannedAlongGround] through the camera this
     * transform gives [base] on a [width]×[height] pixel canvas. The content follows the finger:
     * dragging down pulls the far ground toward the viewer (the view moves downrange), dragging up
     * pushes it away (back toward the tee), and dragging right slides the range right (the view
     * moves left). Built from the transform rather than from the frame last drawn, so the first
     * drag under the follow camera doesn't jump.
     */
    @Suppress("LongParameterList") // A camera, a canvas, two screen points and the bounds.
    fun draggedAlongGround(
        base: RangeCameraPose,
        width: Float,
        height: Float,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        bounds: PanBounds = PanBounds.STANDARD,
    ): ViewTransform = pannedAlongGround(RangeProjection(applyTo(base), width, height), fromX, fromY, toX, toY, bounds)

    /**
     * Plan F8a2p: [zoomedBy] about a screen point (a pinch's centre) instead of the canvas centre.
     * The ground under ([x], [y]) stays under it: zoom narrows the field of view about the centre,
     * then a pan brings the grabbed point back (within the pan bounds). Over the sky, where there's
     * no ground to hold, it zooms about the centre.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun zoomedAbout(
        factor: Double,
        base: RangeCameraPose,
        width: Float,
        height: Float,
        x: Float,
        y: Float,
        bounds: PanBounds = PanBounds.STANDARD,
    ): ViewTransform {
        val zoomed = zoomedBy(factor)
        if (zoomed == this) return this
        val grabbed = RangeProjection(applyTo(base), width, height).unprojectToGround(x, y) ?: return zoomed
        val landed = RangeProjection(zoomed.applyTo(base), width, height).unprojectToGround(x, y) ?: return zoomed
        return zoomed.pannedBy(grabbed.x - landed.x, grabbed.z - landed.z, bounds)
    }

    /** Plan F8a2p: one screen-reader zoom action (TalkBack/VoiceOver), in (positive [steps]) or out. */
    fun zoomedBySteps(steps: Int): ViewTransform = zoomedBy(ZOOM_STEP.pow(steps))

    /** Whether zooming in a step has anywhere to go. */
    val canZoomIn: Boolean get() = zoom < MAX_ZOOM

    /** Whether zooming out a step has anywhere to go. */
    val canZoomOut: Boolean get() = zoom > MIN_ZOOM

    /**
     * The scene's accessibility value on both platforms: "Default view", or the zoom and orbit and
     * (plan F8a2p) how far the view is panned: "Zoom 150 percent, orbit 0 degrees, 40 metres
     * downrange, 12 metres left".
     */
    val description: String
        get() {
            if (isIdentity) return "Default view"
            val parts =
                mutableListOf(
                    "Zoom ${(zoom * PERCENT).roundToInt()} percent",
                    "orbit ${orbitYawDegrees.roundToInt()} degrees",
                )
            val along = (-panZ).roundToInt()
            if (along != 0) parts += if (along > 0) "$along metres downrange" else "${-along} metres back"
            val across = panX.roundToInt()
            if (across != 0) parts += if (across > 0) "$across metres right" else "${-across} metres left"
            return parts.joinToString(", ")
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

        /** Plan F8a2p: one screen-reader zoom step multiplies (or divides) the zoom by this. */
        const val ZOOM_STEP = 1.5

        /** Plan F8a2p: the most one drag step can pan, so a touch near the horizon can't fling the view. */
        const val MAX_PAN_STEP_METERS = 40.0

        private const val PERCENT = 100

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
