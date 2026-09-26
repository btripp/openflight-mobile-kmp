// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import kotlin.test.Test

class GameShotMeasurerTest {
    private val measurer = GameShotMeasurer()

    private fun shot(horizontal: Double?) =
        ShotEvent(
            schemaVersion = 1,
            eventId = "00000000-0000-4000-8000-000000000001",
            timestamp = "2026-09-25T10:00:00",
            club = "7-iron",
            ballSpeedMph = 120.0,
            estimatedCarryYards = 165.0,
            launchAngleVertical = 16.0,
            launchAngleHorizontal = horizontal,
            spinRpm = 7000.0,
            spinAxisDeg = 0.0,
        )

    @Test
    fun atIsaTheCarryIsTheServersAndTotalAddsAnEstimatedRoll() {
        val measured = measurer.measure(shot(horizontal = null), Conditions.ISA, targetBearing = null)

        assertThat(measured.carryYards).isEqualTo(165.0)
        assertThat(measured.carryEstimated).isFalse()
        assertThat(measured.totalYards).isNotNull().isGreaterThan(165.0)
        assertThat(measured.apexYards).isNotNull().isGreaterThan(10.0)
        // No horizontal launch angle: the miss is distance-only.
        assertThat(measured.offlineYards).isNull()
    }

    @Test
    fun aHorizontalLaunchGivesASide() {
        val right = measurer.measure(shot(horizontal = 3.0), Conditions.ISA, targetBearing = null)
        val left = measurer.measure(shot(horizontal = -3.0), Conditions.ISA, targetBearing = null)

        assertThat(right.offlineYards).isNotNull().isGreaterThan(0.0)
        assertThat(left.offlineYards).isNotNull().isLessThan(0.0)
    }
}
