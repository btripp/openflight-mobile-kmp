// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.test.Test

/** Tester request 2026-09-30: the range's shot table, with the tester's 9-iron numbers. */
class RangeShotTableTest {
    private val nineIron =
        ShotDetail(
            timestamp = "2026-09-30T17:55:00",
            club = "9-iron",
            ballSpeedMph = 112.7,
            clubSpeedMph = 85.4,
            smashFactor = 1.32,
            estimatedCarryYards = 166.0,
            carrySpinAdjusted = 144.0,
            launchAngleVertical = 29.0,
            launchAngleHorizontal = 0.4,
            spinRpm = 4_614.0,
        )

    @Test
    fun rowsAreNewestFirstNumberedInSessionOrderWithEveryMeasuredField() {
        val older = storedShot(id = 1, detail = nineIron.copy(ballSpeedMph = 108.9, carrySpinAdjusted = null))
        val newer = storedShot(id = 2, detail = nineIron.copy(spinAxisDeg = -2.0, clubPathDeg = 1.5))

        val table = RangeShotTable.of("s1", listOf(newer, older), RangeNumbers(UnitSystem.IMPERIAL))

        assertThat(table.headers).containsExactly(
            "#",
            "Club",
            "Ball mph",
            "Club mph",
            "Smash",
            "Carry yds",
            "Launch °",
            "Dir °",
            "Spin rpm",
            "Axis °",
            "Path °",
        )
        assertThat(table.rows.map { it.id }).containsExactly("2", "1")
        assertThat(table.rows[0].cells).containsExactly(
            "2",
            "9-Iron",
            "112.7",
            "85.4",
            "1.32",
            "144",
            "29.0",
            "+0.4",
            "4,614",
            "-2.0",
            "+1.5",
        )
        // No spin-adjusted carry: the table carry, like the CARRY tile. Missing values are "—".
        assertThat(table.rows[1].cells[5]).isEqualTo("166")
        assertThat(table.rows[1].cells[9]).isEqualTo("—")
    }

    @Test
    fun theAverageRowAveragesEachColumnOverTheShotsThatHaveIt() {
        val first = storedShot(id = 1, detail = nineIron.copy(ballSpeedMph = 110.0, spinAxisDeg = null))
        val second = storedShot(id = 2, detail = nineIron.copy(ballSpeedMph = 112.0, spinAxisDeg = 3.0))

        val average = RangeShotTable.of("s1", listOf(second, first), RangeNumbers(UnitSystem.IMPERIAL)).average

        assertThat(average?.take(4)).isEqualTo(listOf("Avg", "", "111.0", "85.4"))
        assertThat(average?.get(9)).isEqualTo("+3.0")
        assertThat(average?.get(10)).isEqualTo("—")
    }

    @Test
    fun metricUnitsConvertSpeedsAndCarry() {
        val table =
            RangeShotTable.of(
                "s1",
                listOf(storedShot(id = 1, detail = nineIron)),
                RangeNumbers(UnitSystem.METRIC),
            )

        assertThat(table.headers[2]).isEqualTo("Ball km/h")
        assertThat(table.headers[5]).isEqualTo("Carry m")
        assertThat(table.rows[0].cells[2]).isEqualTo("181.4")
        assertThat(table.rows[0].cells[5]).isEqualTo("132")
    }

    @Test
    fun swingSpeedRepsAreLeftOutAndNoShotsMeansNoAverage() {
        val rep = storedShot(id = 1, detail = ShotDetail(timestamp = "t", club = "Swing Speed", ballSpeedMph = 100.0))

        val table = RangeShotTable.of("s1", listOf(rep), RangeNumbers())

        assertThat(table.rows).isEmpty()
        assertThat(table.average).isNull()
    }
}
