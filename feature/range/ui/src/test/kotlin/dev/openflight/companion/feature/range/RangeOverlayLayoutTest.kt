// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.designsystem.OfWindowClass
import kotlin.test.Test

/** Plan F1b: the range's metrics dock to the side only on an expanded window in landscape. */
class RangeOverlayLayoutTest {
    @Test
    fun expandedLandscapeDocksTheMetrics() {
        assertThat(RangeOverlayLayout.of(OfWindowClass.EXPANDED, isLandscape = true))
            .isEqualTo(RangeOverlayLayout.DOCKED)
    }

    @Test
    fun expandedPortraitOverlaysTheMetrics() {
        assertThat(RangeOverlayLayout.of(OfWindowClass.EXPANDED, isLandscape = false))
            .isEqualTo(RangeOverlayLayout.OVERLAID)
    }

    @Test
    fun compactAndMediumAlwaysOverlayEvenInLandscape() {
        for (windowClass in listOf(OfWindowClass.COMPACT, OfWindowClass.MEDIUM)) {
            assertThat(RangeOverlayLayout.of(windowClass, isLandscape = true)).isEqualTo(RangeOverlayLayout.OVERLAID)
            assertThat(RangeOverlayLayout.of(windowClass, isLandscape = false)).isEqualTo(RangeOverlayLayout.OVERLAID)
        }
    }
}
