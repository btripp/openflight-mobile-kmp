// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.insights.UnitSystem
import kotlin.test.Test

/** Plan F8f: the range's numbers in the chosen units, and the style picker's swatches. */
class RangeNumbersTest {
    private val rollOut = RangeRollOut(carryYards = 264.0, rollYards = 21.0, totalYards = 285.0, carryEstimated = false)

    @Test
    fun imperialKeepsTheWireUnits() {
        val numbers = RangeNumbers(UnitSystem.IMPERIAL)

        assertThat(numbers.speed(151.4)).isEqualTo("151.4")
        assertThat(numbers.distance(264.0)).isEqualTo("264")
        assertThat(numbers.speedUnit).isEqualTo("mph")
        assertThat(numbers.distanceUnit).isEqualTo("yds")
        assertThat(numbers.rollOutSummary(rollOut)).isEqualTo("Total est. 285 yd · roll 21")
    }

    @Test
    fun metricConvertsSpeedAndDistance() {
        val numbers = RangeNumbers(UnitSystem.METRIC)

        assertThat(numbers.speed(100.0)).isEqualTo("160.9")
        assertThat(numbers.distance(100.0)).isEqualTo("91")
        assertThat(numbers.speedUnit).isEqualTo("km/h")
        assertThat(numbers.distanceUnit).isEqualTo("m")
        assertThat(numbers.rollOutSummary(rollOut.copy(carryEstimated = true)))
            .isEqualTo("Total est. 261 m · roll 19 · carry est.")
        assertThat(rollOut.copy(units = UnitSystem.METRIC).totalLabel).isEqualTo("est. 260")
    }

    @Test
    fun missingValuesStayMissing() {
        assertThat(RangeNumbers(UnitSystem.METRIC).speed(null)).isEqualTo("—")
        assertThat(RangeNumbers().distance(null)).isEqualTo("—")
    }

    @Test
    fun everyStyleHasASwatchFittedInsideItsBox() {
        for (style in ShotTrailStyle.entries) {
            val swatch = ShotTrailSwatch(style, RangeTheme.DAY) { RecordingPathSink() }
            assertThat(swatch.trail.layers.count { it.visible }).isGreaterThan(0)
            assertThat(swatch.minX).isLessThan(swatch.maxX)
            assertThat(swatch.minY).isLessThan(swatch.maxY)

            val (scale, offsetX, offsetY) = swatch.fit(width = 96f, height = 48f, padding = 4f).toList()
            assertThat(swatch.minX * scale + offsetX).isGreaterThan(3.9f)
            assertThat(swatch.maxX * scale + offsetX).isLessThan(92.1f)
            assertThat(swatch.minY * scale + offsetY).isGreaterThan(3.9f)
            assertThat(swatch.maxY * scale + offsetY).isLessThan(44.1f)
        }
    }
}
