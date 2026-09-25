// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.Wind
import kotlin.test.Test

class WindFrameTest {
    private val target = TargetBearing(30.0)

    @Test
    fun windFromTheTargetIsAHeadwind() {
        val air = Wind(speedMps = 5.0, fromDegrees = 30.0).toSimulatorFrame(target)

        assertThat(air.z).isCloseTo(-5.0, 1e-9)
        assertThat(air.x).isCloseTo(0.0, 1e-9)
    }

    @Test
    fun windFromBehindIsATailwind() {
        val air = Wind(speedMps = 5.0, fromDegrees = 210.0).toSimulatorFrame(target)

        assertThat(air.z).isCloseTo(5.0, 1e-9)
    }

    @Test
    fun windFromTheLeftBlowsRight() {
        val air = Wind(speedMps = 5.0, fromDegrees = 300.0).toSimulatorFrame(target)

        assertThat(air.x).isCloseTo(5.0, 1e-9)
        assertThat(air.z).isCloseTo(0.0, 1e-9)
    }

    @Test
    fun calmIsZero() {
        assertThat(Wind.CALM.toSimulatorFrame(target)).isEqualTo(Vec3.ZERO)
    }
}
