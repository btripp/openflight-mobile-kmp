// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ShotMetricFormatterTest {
    @Test
    fun nullAndNonFiniteValuesRenderAsAnEmDash() {
        assertThat(ShotMetricFormatter.number(null, decimals = 1)).isEqualTo("—")
        assertThat(ShotMetricFormatter.number(Double.NaN, decimals = 1)).isEqualTo("—")
        assertThat(ShotMetricFormatter.number(Double.POSITIVE_INFINITY, decimals = 0)).isEqualTo("—")
    }

    @Test
    fun fixedFractionLengthPadsAndRounds() {
        assertThat(ShotMetricFormatter.number(151.4, decimals = 1)).isEqualTo("151.4")
        assertThat(ShotMetricFormatter.number(264.0, decimals = 0)).isEqualTo("264")
        assertThat(ShotMetricFormatter.number(1.47, decimals = 2)).isEqualTo("1.47")
        assertThat(ShotMetricFormatter.number(1.5, decimals = 2)).isEqualTo("1.50")
        assertThat(ShotMetricFormatter.number(103.26, decimals = 1)).isEqualTo("103.3")
        assertThat(ShotMetricFormatter.number(263.6, decimals = 0)).isEqualTo("264")
    }

    @Test
    fun thousandsAreGroupedWithCommas() {
        assertThat(ShotMetricFormatter.number(2380.0, decimals = 0)).isEqualTo("2,380")
        assertThat(ShotMetricFormatter.number(1234567.891, decimals = 1)).isEqualTo("1,234,567.9")
        assertThat(ShotMetricFormatter.number(999.0, decimals = 0)).isEqualTo("999")
    }

    @Test
    fun negativesKeepTheirSignAndSignedAddsPlusToPositives() {
        assertThat(ShotMetricFormatter.number(-1.3, decimals = 1)).isEqualTo("-1.3")
        assertThat(ShotMetricFormatter.number(-3.4, decimals = 1, signed = true)).isEqualTo("-3.4")
        assertThat(ShotMetricFormatter.number(2.1, decimals = 1, signed = true)).isEqualTo("+2.1")
        assertThat(ShotMetricFormatter.number(0.0, decimals = 1, signed = true)).isEqualTo("0.0")
    }

    @Test
    fun aNegativeThatRoundsToZeroDropsTheSign() {
        assertThat(ShotMetricFormatter.number(-0.04, decimals = 1)).isEqualTo("0.0")
    }
}
