// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.openflight.companion.core.flight.RangeCameraPlanner
import dev.openflight.companion.core.flight.Vec3
import kotlin.math.sqrt
import kotlin.test.Test

/** Plan F8a1: the view controls' clamps, the pose they produce and the screen↔world round trip. */
class ViewTransformTest {
    private val base = RangeCameraPlanner().pose

    @Test
    fun zoomIsClampedBetweenHalfAndFourTimes() {
        assertThat(ViewTransform().zoomedBy(10.0).zoom).isEqualTo(ViewTransform.MAX_ZOOM)
        assertThat(ViewTransform().zoomedBy(0.1).zoom).isEqualTo(ViewTransform.MIN_ZOOM)
        assertThat(ViewTransform().zoomedBy(2.0).zoomedBy(1.5).zoom).isCloseTo(3.0, 1e-9)
    }

    @Test
    fun aNonFiniteOrNonPositiveZoomFactorIsIgnored() {
        assertThat(ViewTransform().zoomedBy(Double.NaN)).isEqualTo(ViewTransform.IDENTITY)
        assertThat(ViewTransform().zoomedBy(0.0)).isEqualTo(ViewTransform.IDENTITY)
        assertThat(ViewTransform().zoomedBy(-2.0)).isEqualTo(ViewTransform.IDENTITY)
    }

    @Test
    fun panIsClampedToTheRangeBounds() {
        val bounds = PanBounds.STANDARD
        val far = ViewTransform().pannedBy(1_000.0, -1_000.0)
        assertThat(far.panX).isEqualTo(bounds.maxX)
        assertThat(far.panZ).isEqualTo(bounds.minZ)
        val back = ViewTransform().pannedBy(-1_000.0, 1_000.0)
        assertThat(back.panX).isEqualTo(bounds.minX)
        assertThat(back.panZ).isEqualTo(bounds.maxZ)
        // The pivot (about 109 m downrange) can reach the far end of the 390 m range and the tee.
        assertThat(bounds.minZ).isCloseTo(-(390.0 - RangeCameraPlanner.TARGET_DOWNRANGE_METERS), 1e-9)
        assertThat(bounds.maxZ).isEqualTo(RangeCameraPlanner.TARGET_DOWNRANGE_METERS)
        assertThat(bounds.maxX).isEqualTo(90.0)
    }

    @Test
    fun orbitIsClampedToSixtyDegreesEitherWay() {
        assertThat(ViewTransform().orbitedBy(200.0).orbitYawDegrees).isEqualTo(60.0)
        assertThat(ViewTransform().orbitedBy(-200.0).orbitYawDegrees).isEqualTo(-60.0)
        assertThat(ViewTransform().orbitedBy(20.0).orbitedBy(15.0).orbitYawDegrees).isCloseTo(35.0, 1e-9)
    }

    @Test
    fun resetIsTheIdentityWhichLeavesThePoseUntouched() {
        val moved = ViewTransform().zoomedBy(2.0).pannedBy(5.0, -5.0).orbitedBy(10.0)
        assertThat(moved.isIdentity).isFalse()
        assertThat(ViewTransform.IDENTITY.isIdentity).isTrue()
        assertThat(ViewTransform.IDENTITY.applyTo(base)).isSameInstanceAs(base)
    }

    @Test
    fun zoomNarrowsTheFieldOfViewWithoutMovingTheCamera() {
        val pose = ViewTransform(zoom = 2.0).applyTo(base)
        assertThat(pose.position).isEqualTo(base.position)
        assertThat(pose.target).isEqualTo(base.target)
        assertThat(pose.verticalFovDegrees).isLessThan(base.verticalFovDegrees)

        // A point near the centre sits twice as far from it on screen at 2×.
        val point = Vec3(3.0, 0.0, -120.0)
        val plain = RangeProjection(base, WIDTH, HEIGHT).project(point)
        val zoomed = RangeProjection(pose, WIDTH, HEIGHT).project(point)
        assertThat((zoomed.x - WIDTH / 2).toDouble()).isCloseTo(2.0 * (plain.x - WIDTH / 2), 0.01)
    }

    @Test
    fun orbitKeepsTheCameraDistanceAndHeightAndSwingsItAcross() {
        val pose = ViewTransform(orbitYawDegrees = 45.0).applyTo(base)
        assertThat(pose.target).isEqualTo(base.target)
        assertThat(pose.position.y).isEqualTo(base.position.y)
        assertThat(horizontalDistance(pose.position, pose.target))
            .isCloseTo(horizontalDistance(base.position, base.target), 1e-9)
        // +yaw moves the camera to the right of the target line.
        assertThat(pose.position.x).isGreaterThan(0.0)
    }

    @Test
    fun panMovesCameraAndTargetTogether() {
        val pose = ViewTransform(panX = 10.0, panZ = -40.0).applyTo(base)
        assertThat(pose.position).isEqualTo(base.position + Vec3(10.0, 0.0, -40.0))
        assertThat(pose.target).isEqualTo(base.target + Vec3(10.0, 0.0, -40.0))
    }

    @Test
    fun groundPointsRoundTripThroughScreenAndBack() {
        val transforms =
            listOf(
                ViewTransform.IDENTITY,
                ViewTransform(zoom = 2.5, panX = -12.0, panZ = -60.0, orbitYawDegrees = 35.0),
                ViewTransform(zoom = 0.5, orbitYawDegrees = -60.0),
            )
        val points = listOf(Vec3(0.0, 0.0, -50.0), Vec3(-20.0, 0.0, -180.0), Vec3(15.0, 0.0, -240.0))
        for (transform in transforms) {
            val projection = RangeProjection(transform.applyTo(base), WIDTH, HEIGHT)
            for (point in points) {
                val screen = projection.project(point)
                val back = projection.unprojectToGround(screen.x, screen.y)
                assertThat(back).isNotNull()
                assertThat(back!!.x).isCloseTo(point.x, 0.05)
                assertThat(back.z).isCloseTo(point.z, 0.05)
            }
        }
    }

    @Test
    fun theSkyHasNoGroundUnderIt() {
        val projection = RangeProjection(base, WIDTH, HEIGHT)
        assertThat(projection.unprojectToGround(WIDTH / 2, 0f)).isNull()
    }

    @Test
    fun panningAlongTheGroundKeepsTheGrabbedPointUnderTheFinger() {
        val start = ViewTransform(zoom = 1.5, orbitYawDegrees = 20.0)
        val projection = RangeProjection(start.applyTo(base), WIDTH, HEIGHT)
        val grabbed = projection.unprojectToGround(500f, 1_500f)!!

        val panned = start.pannedAlongGround(projection, 500f, 1_500f, 560f, 1_450f)
        val after = RangeProjection(panned.applyTo(base), WIDTH, HEIGHT).project(grabbed)

        assertThat(after.x.toDouble()).isCloseTo(560.0, 0.5)
        assertThat(after.y.toDouble()).isCloseTo(1_450.0, 0.5)
    }

    // Plan F8a2p: the map-like gestures.

    @Test
    fun aOneFingerDragDownMovesTheViewDownrangeAndUpMovesItBack() {
        val down = ViewTransform.IDENTITY.draggedAlongGround(base, WIDTH, HEIGHT, 540f, 1_300f, 540f, 1_500f)
        assertThat(down.panZ).isLessThan(0.0)
        assertThat(down.panX).isCloseTo(0.0, 1e-6)

        val up = ViewTransform.IDENTITY.draggedAlongGround(base, WIDTH, HEIGHT, 540f, 1_500f, 540f, 1_300f)
        assertThat(up.panZ).isGreaterThan(0.0)
    }

    @Test
    fun aOneFingerDragRightSlidesTheRangeRightSoTheViewMovesLeft() {
        val right = ViewTransform.IDENTITY.draggedAlongGround(base, WIDTH, HEIGHT, 400f, 1_400f, 700f, 1_400f)
        assertThat(right.panX).isLessThan(0.0)
        assertThat(right.panZ).isCloseTo(0.0, 1e-6)
    }

    @Test
    fun theDraggedGroundFollowsTheFinger() {
        val start = ViewTransform(zoom = 1.4)
        val grabbed = RangeProjection(start.applyTo(base), WIDTH, HEIGHT).unprojectToGround(600f, 1_450f)!!

        val dragged = start.draggedAlongGround(base, WIDTH, HEIGHT, 600f, 1_450f, 640f, 1_500f)
        val after = RangeProjection(dragged.applyTo(base), WIDTH, HEIGHT).project(grabbed)

        assertThat(after.x.toDouble()).isCloseTo(640.0, 0.5)
        assertThat(after.y.toDouble()).isCloseTo(1_500.0, 0.5)
    }

    @Test
    fun aDragNearTheHorizonPansAtMostOneStepAndStaysInBounds() {
        val horizon = RangeProjection(base, WIDTH, HEIGHT).horizonY()
        val step =
            ViewTransform.IDENTITY.draggedAlongGround(
                base,
                WIDTH,
                HEIGHT,
                540f,
                horizon + 2f,
                540f,
                horizon + 40f,
            )
        assertThat(sqrt(step.panX * step.panX + step.panZ * step.panZ))
            .isLessThan(ViewTransform.MAX_PAN_STEP_METERS + 1e-6)

        var flung = ViewTransform.IDENTITY
        repeat(40) { flung = flung.draggedAlongGround(base, WIDTH, HEIGHT, 540f, horizon + 2f, 540f, horizon + 40f) }
        assertThat(flung.panZ).isEqualTo(PanBounds.STANDARD.minZ)
    }

    @Test
    fun aPinchZoomsAboutItsCentre() {
        val centreX = 300f
        val centreY = 1_300f
        val grabbed = RangeProjection(base, WIDTH, HEIGHT).unprojectToGround(centreX, centreY)!!

        val zoomed = ViewTransform.IDENTITY.zoomedAbout(2.0, base, WIDTH, HEIGHT, centreX, centreY)
        assertThat(zoomed.zoom).isCloseTo(2.0, 1e-9)
        val after = RangeProjection(zoomed.applyTo(base), WIDTH, HEIGHT).project(grabbed)

        assertThat(after.x.toDouble()).isCloseTo(centreX.toDouble(), 0.5)
        assertThat(after.y.toDouble()).isCloseTo(centreY.toDouble(), 0.5)
    }

    @Test
    fun aPinchOverTheSkyZoomsAboutTheCentreAndTheClampHolds() {
        val sky = ViewTransform.IDENTITY.zoomedAbout(2.0, base, WIDTH, HEIGHT, WIDTH / 2, 10f)
        assertThat(sky).isEqualTo(ViewTransform(zoom = 2.0))

        val atMax = ViewTransform(zoom = ViewTransform.MAX_ZOOM)
        assertThat(atMax.zoomedAbout(3.0, base, WIDTH, HEIGHT, 300f, 1_300f)).isSameInstanceAs(atMax)
    }

    @Test
    fun theScreenReaderZoomStepsByOneAndAHalfWithinTheClamp() {
        assertThat(ViewTransform.IDENTITY.zoomedBySteps(1).zoom).isCloseTo(1.5, 1e-9)
        assertThat(ViewTransform.IDENTITY.zoomedBySteps(-1).zoom).isCloseTo(1 / 1.5, 1e-9)
        assertThat(ViewTransform(zoom = ViewTransform.MAX_ZOOM).canZoomIn).isFalse()
        assertThat(ViewTransform(zoom = ViewTransform.MIN_ZOOM).canZoomOut).isFalse()
        assertThat(ViewTransform.IDENTITY.canZoomIn && ViewTransform.IDENTITY.canZoomOut).isTrue()
    }

    @Test
    fun theDescriptionNamesZoomOrbitAndPan() {
        assertThat(ViewTransform.IDENTITY.description).isEqualTo("Default view")
        assertThat(ViewTransform(zoom = 2.5).description).isEqualTo("Zoom 250 percent, orbit 0 degrees")
        assertThat(ViewTransform(panX = -12.2, panZ = -40.0).description)
            .isEqualTo("Zoom 100 percent, orbit 0 degrees, 40 metres downrange, 12 metres left")
        assertThat(ViewTransform(panX = 3.0, panZ = 20.0).description)
            .isEqualTo("Zoom 100 percent, orbit 0 degrees, 20 metres back, 3 metres right")
    }

    private fun horizontalDistance(
        a: Vec3,
        b: Vec3,
    ): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private companion object {
        const val WIDTH = 1080f
        const val HEIGHT = 1920f
    }
}
