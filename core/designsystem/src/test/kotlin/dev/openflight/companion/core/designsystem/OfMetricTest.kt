// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class OfMetricTest {
    @Test
    fun degreeUnitAttachesTightlyToTheValue() {
        assertThat(unitSuffix("°")).isEqualTo("°")
    }

    @Test
    fun wordLikeUnitsKeepALeadingSpace() {
        assertThat(unitSuffix("mph")).isEqualTo(" mph")
        assertThat(unitSuffix("MPH")).isEqualTo(" MPH")
        assertThat(unitSuffix("rpm")).isEqualTo(" rpm")
        assertThat(unitSuffix("YDS")).isEqualTo(" YDS")
    }

    @Test
    fun contentDescriptionReadsAsOneSensiblePhrase() {
        assertThat(metricContentDescription("BALL SPEED", "139.1", "MPH"))
            .isEqualTo("Ball speed, 139.1 miles per hour")
        assertThat(metricContentDescription("Club speed", "104.1", "mph"))
            .isEqualTo("Club speed, 104.1 miles per hour")
        assertThat(metricContentDescription("CARRY", "231", "YDS"))
            .isEqualTo("Carry, 231 yards")
        assertThat(metricContentDescription("Spin", "2,380", "rpm"))
            .isEqualTo("Spin, 2,380 revolutions per minute")
        assertThat(metricContentDescription("Launch", "9.5", "°"))
            .isEqualTo("Launch, 9.5 degrees")
    }

    @Test
    fun contentDescriptionForAMissingReadingSaysSo() {
        assertThat(metricContentDescription("Smash", "—")).isEqualTo("Smash, no reading")
    }

    @Test
    fun contentDescriptionWithNoUnitOmitsIt() {
        assertThat(metricContentDescription("Smash", "1.47")).isEqualTo("Smash, 1.47")
    }
}
