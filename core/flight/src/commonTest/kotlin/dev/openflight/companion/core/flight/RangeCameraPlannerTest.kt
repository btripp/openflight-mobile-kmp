// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test

/**
 * Ported from `ios/OpenFlightTests/RangeCameraPlannerTests.swift`, with the plan F8a2p pose: the
 * reference's shape (behind the tee, on the target line, looking downrange) with the raised camera.
 */
class RangeCameraPlannerTest {
    @Test
    fun fixedCameraPoseLooksDownrangeFromBehindTee() {
        val pose = RangeCameraPlanner().pose

        assertThat(pose.position).isEqualTo(Vec3(0.0, 12.0, 36.0))
        assertThat(pose.target).isEqualTo(Vec3(0.0, 0.0, -108.8528))
        assertThat(pose.verticalFovDegrees).isEqualTo(45.0)
        assertThat(pose.position.z).isGreaterThan(0.0)
        assertThat(pose.target.z).isLessThan(0.0)
    }

    @Test
    fun theTeeCameraLooksDownSoTheHorizonSitsAboveTheMiddle() {
        val pose = RangeCameraPlanner().pose
        val drop = pose.position.y - pose.target.y
        val run = pose.position.z - pose.target.z
        // The horizon's fraction from the top: 0.5 − tan(pitch) / (2 tan(fov / 2)).
        val horizon = 0.5 - (drop / run) / (2 * tan(pose.verticalFovDegrees / 2 * PI / 180))

        assertThat(horizon).isCloseTo(RangeCameraPlanner.HORIZON_FRACTION, 0.001)
    }
}
