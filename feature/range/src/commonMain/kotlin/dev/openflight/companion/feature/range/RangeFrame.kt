// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.RangeCameraPose
import dev.openflight.companion.core.flight.RangeQualityProfile
import dev.openflight.companion.core.flight.RangeSceneDescription
import dev.openflight.companion.core.flight.Vec3

/**
 * Everything a range renderer draws for one camera pose, kept projected (plan F8c1): the [scene],
 * the shown flight's [geometry], its [landing] marker and [tracer], the F8a1 [overlay] and the
 * estimated roll-out. Shared by the Android Canvas and the iOS Canvas (plan F8c2), which only paint
 * what this holds, in this order:
 *
 * 1. the backdrop: the [RangeVisualStyle.sky] gradient from 0 to [RangeScene.backdropHorizon];
 *    the [RangeScene.sky]'s sun glow and disc, then its visible ridges far to near (plan F8a2a);
 *    [RangeVisualStyle.ground] from the horizon down;
 * 2. the ground (plan F8a2p): [RangeScene.fairway], [RangeScene.stripes] with
 *    [RangeVisualStyle.stripeGradient], [RangeScene.groundPolygons], then the haze overlay
 *    ([RangeHaze.overlayStops] from [RangeScene.hazeTopY] to [RangeScene.hazeBottomY]) from the
 *    horizon down;
 * 3. [RangeScene.polygons], then the trees in [RangeScene.treeOrder] (trunk, then each crown
 *    tone), then the labels that are [WorldLabel.drawn] (not under the [setObstructions] UI);
 * 4. the [overlay] (club-group strokes, landing dots, then the selection) when there is one;
 * 5. with no [geometry]: the roll-out if there's an overlay, and stop;
 * 6. once landed (progress ≥ 1): the [landing] polygons and the roll-out;
 * 7. the ball's shadow (an oval) when [shadowVisible], the [tracer]'s glow then its core ribbon,
 *    and the ball at the tracer's tip when it's in front of the camera.
 *
 * [prepare] re-projects only when the pose or the canvas changed since the last frame, and rewrites
 * the same [PathSink]s and arrays: nothing in it allocates.
 */
@Suppress("TooManyFunctions") // Small, allocation-free per-frame steps.
class RangeFrame<P : PathSink>(
    val style: RangeVisualStyle,
    /** The size of the platform's club palette, for the overlay's colour groups. */
    private val clubPaletteSize: Int,
    private val newPath: () -> P,
    description: RangeSceneDescription = RangeSceneDescription.standard(QUALITY.treeCount),
) {
    val scene: RangeScene<P> = RangeScene(description, style, newPath)

    /** The camera of the last prepared frame; gestures pan along the ground and taps select through it. */
    var projection: RangeProjection? = null
        private set
    private var projectedPose: RangeCameraPose? = null
    private var dirty = true

    /** Plan F8a2p: the overlaid UI the labels and far markers keep clear of. */
    private val obstructions = RangeObstructions()
    private var obstructionsDirty = true
    private var minLabelPixels = style.minLabelSize
    private var maxLabelPixels = style.maxLabelSize

    var geometry: FlightGeometry? = null
        private set
    private var geometryFor: ActiveFlight? = null

    var landing: List<WorldPolygon<P>> = emptyList()
        private set

    /** The tracer's core and (plan F8a2a) its glow. */
    val tracer: TracerRibbon<P> = TracerRibbon(newPath(), newPath(), style.tracerGlowWidthFactor)

    /** Plan F8a1: the overlay's static trajectories, when overlaying. */
    var overlay: OverlayGeometry<P>? = null
        private set
    private var overlayFor: List<OverlayFlight>? = null

    /** Plan F8a1: the estimated roll-out, from the carry landing to the total dot, in scene space. */
    private var rollOutFor: Pair<RangeRollOut, FlightTrajectory>? = null
    private var rollOutStart: Vec3? = null
    private var rollOutEnd: Vec3? = null
    private val rollOutScreen = FloatArray(ROLL_OUT_COORDINATES)

    /** Both roll-out ends are in front of the camera. */
    var rollOutVisible = false
        private set
    val rollOutStartX: Float get() = rollOutScreen[START_X]
    val rollOutStartY: Float get() = rollOutScreen[START_Y]
    val rollOutEndX: Float get() = rollOutScreen[END_X]
    val rollOutEndY: Float get() = rollOutScreen[END_Y]

    /** The flight's fractional sample index for the prepared progress (see [FlightGeometry.sampleAt]). */
    var flightPosition = 0f
        private set

    /** The shadow oval (centre and radii, pixels) at [flightPosition]. */
    var shadowVisible = false
        private set
    var shadowX = 0f
        private set
    var shadowY = 0f
        private set
    var shadowRadiusX = 0f
        private set
    var shadowRadiusY = 0f
        private set

    /** The ball's radius (pixels) at [flightPosition]; drawn at the tracer's tip. */
    var ballRadius = 0f
        private set

    /** Sets the canvas size; the first call also sets the starting [pose]. */
    fun resize(
        width: Float,
        height: Float,
        pose: RangeCameraPose,
    ) {
        val current = projection
        if (current == null) {
            projection = RangeProjection(pose, width, height)
            projectedPose = pose
        } else if (current.width != width || current.height != height) {
            current.update(projectedPose ?: pose, width, height)
        }
        dirty = true
    }

    /**
     * Plan F8a2p: the overlaid UI's rectangles in canvas pixels (`left, top, right, bottom` each),
     * which the yardage labels and far markers keep clear of ([RangeScene.obstruct]). Cheap to call
     * every frame: only a change re-applies them.
     */
    fun setObstructions(packed: FloatArray) {
        if (obstructions.set(packed)) obstructionsDirty = true
    }

    /**
     * Plan F8a2p: the labels' font size range in pixels (the style's `minLabelSize` and
     * `maxLabelSize` at the platform's density), for measuring their boxes against the obstructions.
     */
    fun setLabelPixels(
        minPixels: Float,
        maxPixels: Float,
    ) {
        if (minPixels == minLabelPixels && maxPixels == maxLabelPixels) return
        minLabelPixels = minPixels
        maxLabelPixels = maxPixels
        obstructionsDirty = true
    }

    /** The flight to show (the same instance keeps its geometry); needs a canvas ([resize]) first. */
    fun setFlight(
        flight: ActiveFlight?,
        segments: Int = QUALITY.tracerPointCount,
    ) {
        val projection = projection ?: return
        if (flight === geometryFor) return
        geometryFor = flight
        geometry = flight?.let { FlightGeometry.build(it.trajectory, projection, segments) }
        landing = geometry?.let { scene.landingMarker(it.landing) }.orEmpty()
        tracer.ensureCapacity(segments)
        dirty = true
    }

    /** Plan F8a1: the overlay to draw (built once per list of flights) and its highlighted shot. */
    fun setOverlay(
        flights: List<OverlayFlight>,
        selectedId: String?,
    ) {
        if (flights !== overlayFor) {
            overlayFor = flights
            overlay = if (flights.isEmpty()) null else OverlayGeometry(flights, clubPaletteSize, newPath)
            dirty = true
        }
        if (overlay?.select(selectedId) == true) dirty = true
    }

    /** Plan F8a1: the roll-out marker for [rollOut], continuing [trajectory]'s landing. */
    fun setRollOut(
        rollOut: RangeRollOut?,
        trajectory: FlightTrajectory?,
    ) {
        val key = if (rollOut != null && trajectory != null) rollOut to trajectory else null
        if (key == rollOutFor) return
        rollOutFor = key
        val landingPoint = trajectory?.points?.lastOrNull()?.positionMeters
        rollOutStart = landingPoint?.let { RangeProjection.flightToScene(it).copy(y = 0.0) }
        rollOutEnd =
            if (rollOut != null && trajectory != null) {
                RangeProjection.flightToScene(rollOutEnd(trajectory, rollOut.rollYards)).copy(y = 0.0)
            } else {
                null
            }
        dirty = true
    }

    /**
     * Projects everything for [pose] (only if it or anything else changed) and builds the tracer,
     * shadow and ball for playback [progress] in 0..1. Returns false before the first [resize].
     */
    fun prepare(
        pose: RangeCameraPose,
        progress: Float,
    ): Boolean {
        val projection = projection ?: return false
        if (dirty || pose != projectedPose) {
            projection.update(pose, projection.width, projection.height)
            projectedPose = pose
            scene.project(projection)
            geometry?.reproject(projection)
            overlay?.reproject(projection)
            for (index in landing.indices) landing[index].project(projection, scene.scratch)
            projectRollOut(projection)
            dirty = false
            obstructionsDirty = true
        }
        if (obstructionsDirty) {
            scene.obstruct(obstructions, style.labelHeightMeters, minLabelPixels, maxLabelPixels)
            obstructionsDirty = false
        }
        geometry?.let { prepareFlight(it, progress) }
        return true
    }

    /** The tracer, shadow and ball of [geometry] at playback [progress]. */
    private fun prepareFlight(
        geometry: FlightGeometry,
        progress: Float,
    ) {
        val at = geometry.sampleAt(progress)
        flightPosition = at
        shadowX = geometry.valueAt(geometry.shadowXs, at)
        shadowY = geometry.valueAt(geometry.shadowYs, at)
        shadowVisible = !shadowX.isNaN() && !shadowY.isNaN()
        if (shadowVisible) {
            shadowRadiusX = geometry.valueAt(geometry.shadowRadiiX, at)
            shadowRadiusY = geometry.valueAt(geometry.shadowRadiiY, at)
        }
        tracer.build(geometry, at)
        ballRadius = geometry.valueAt(geometry.ballRadii, at)
    }

    private fun projectRollOut(projection: RangeProjection) {
        val start = rollOutStart
        val end = rollOutEnd
        rollOutVisible =
            start != null &&
            end != null &&
            projection.projectInto(start.x, start.y, start.z, rollOutScreen, START_X) &&
            projection.projectInto(end.x, end.y, end.z, rollOutScreen, END_X)
    }

    companion object {
        /**
         * The one quality profile both renderers use (plan F8c1): the scene's tree count and the
         * tracer's segment count.
         */
        val QUALITY: RangeQualityProfile = RangeQualityProfile.BALANCED

        /** Indices into the roll-out's projected start and end points. */
        private const val START_X = 0
        private const val START_Y = 1
        private const val END_X = 2
        private const val END_Y = 3
        private const val ROLL_OUT_COORDINATES = 4
    }
}
