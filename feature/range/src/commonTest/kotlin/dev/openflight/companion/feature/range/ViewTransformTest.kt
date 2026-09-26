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
        // The pivot (145 m downrange) can reach the far end of the 390 m range and the tee.
        assertThat(bounds.minZ).isEqualTo(-245.0)
        assertThat(bounds.maxZ).isEqualTo(145.0)
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

    private fun horizontalDistance(
        a: Vec3,
        b: Vec3,
    ): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private companion object {
        const val WIDTH = 1080f
        const val HEIGHT = 1920f
    }
}
