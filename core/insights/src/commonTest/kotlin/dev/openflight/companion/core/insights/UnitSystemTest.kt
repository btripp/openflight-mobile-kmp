// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class UnitSystemTest {
    @Test
    fun imperialSpeedAndDistancePassThroughUnchanged() {
        assertThat(convertSpeedFromMph(100.0, UnitSystem.IMPERIAL)).isEqualTo(100.0)
        assertThat(convertDistanceFromYards(250.0, UnitSystem.IMPERIAL)).isEqualTo(250.0)
    }

    @Test
    fun metricConvertsMphToKmhAndYardsToMeters() {
        assertThat(convertSpeedFromMph(100.0, UnitSystem.METRIC)).isEqualTo(160.934)
        assertThat(convertDistanceFromYards(100.0, UnitSystem.METRIC)).isEqualTo(91.44)
    }

    @Test
    fun unitLabelsMatchTheWebUi() {
        assertThat(speedUnitLabel(UnitSystem.IMPERIAL)).isEqualTo("mph")
        assertThat(speedUnitLabel(UnitSystem.METRIC)).isEqualTo("km/h")
        assertThat(distanceUnitLabel(UnitSystem.IMPERIAL)).isEqualTo("yds")
        assertThat(distanceUnitLabel(UnitSystem.METRIC)).isEqualTo("m")
    }

    @Test
    fun formatSpeedAppendsAUnitWithASpace() {
        assertThat(formatSpeed(151.4, UnitSystem.IMPERIAL)).isEqualTo("151.4 mph")
        assertThat(formatSpeed(100.0, UnitSystem.METRIC)).isEqualTo("160.9 km/h")
        assertThat(formatSpeed(151.44, UnitSystem.IMPERIAL, digits = 0)).isEqualTo("151 mph")
    }

    @Test
    fun formatDistanceAppendsAUnitWithASpace() {
        assertThat(formatDistance(264.0, UnitSystem.IMPERIAL)).isEqualTo("264 yds")
        assertThat(formatDistance(100.0, UnitSystem.METRIC)).isEqualTo("91 m")
        assertThat(formatDistance(100.0, UnitSystem.METRIC, digits = 1)).isEqualTo("91.4 m")
    }

    @Test
    fun formatDegreesRendersTightWithNoSpace() {
        assertThat(formatDegrees(9.5)).isEqualTo("9.5°")
        assertThat(formatDegrees(-3.4)).isEqualTo("-3.4°")
        assertThat(formatDegrees(12.649, digits = 1)).isEqualTo("12.6°")
    }
}
