// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.test.Test

/** Ported one-to-one from `ios/OpenFlightTests/RangeCameraPlannerTests.swift`. */
class RangeCameraPlannerTest {
    @Test
    fun fixedCameraPoseLooksDownrangeFromBehindTee() {
        val pose = RangeCameraPlanner().pose

        assertThat(pose.position).isEqualTo(Vec3(0.0, 3.4, 8.0))
        assertThat(pose.target).isEqualTo(Vec3(0.0, 9.0, -145.0))
        assertThat(pose.position.z).isGreaterThan(0.0)
        assertThat(pose.target.z).isLessThan(0.0)
    }
}
