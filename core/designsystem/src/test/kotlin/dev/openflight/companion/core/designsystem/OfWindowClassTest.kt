// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** Material 3 width breakpoints: compact < 600 dp <= medium < 840 dp <= expanded. */
class OfWindowClassTest {
    @Test
    fun widthsUnder600dpAreCompact() {
        assertThat(OfWindowClass.fromWidthDp(0)).isEqualTo(OfWindowClass.COMPACT)
        assertThat(OfWindowClass.fromWidthDp(411)).isEqualTo(OfWindowClass.COMPACT)
        assertThat(OfWindowClass.fromWidthDp(599)).isEqualTo(OfWindowClass.COMPACT)
    }

    @Test
    fun widthsFrom600dpUnder840dpAreMedium() {
        assertThat(OfWindowClass.fromWidthDp(600)).isEqualTo(OfWindowClass.MEDIUM)
        assertThat(OfWindowClass.fromWidthDp(800)).isEqualTo(OfWindowClass.MEDIUM)
        assertThat(OfWindowClass.fromWidthDp(839)).isEqualTo(OfWindowClass.MEDIUM)
    }

    @Test
    fun widthsFrom840dpAreExpanded() {
        assertThat(OfWindowClass.fromWidthDp(840)).isEqualTo(OfWindowClass.EXPANDED)
        assertThat(OfWindowClass.fromWidthDp(1280)).isEqualTo(OfWindowClass.EXPANDED)
        assertThat(OfWindowClass.fromWidthDp(1600)).isEqualTo(OfWindowClass.EXPANDED)
    }

    @Test
    fun compactWindowsGetABottomBarAndWiderOnesARail() {
        assertThat(OfNavigationLayout.of(OfWindowClass.COMPACT, showNavigation = true))
            .isEqualTo(OfNavigationLayout.BOTTOM_BAR)
        assertThat(OfNavigationLayout.of(OfWindowClass.MEDIUM, showNavigation = true))
            .isEqualTo(OfNavigationLayout.RAIL)
        assertThat(OfNavigationLayout.of(OfWindowClass.EXPANDED, showNavigation = true))
            .isEqualTo(OfNavigationLayout.RAIL)
    }

    @Test
    fun hiddenNavigationShowsNothingOnAnyWidth() {
        for (windowClass in OfWindowClass.entries) {
            assertThat(OfNavigationLayout.of(windowClass, showNavigation = false)).isEqualTo(OfNavigationLayout.NONE)
        }
    }

    @Test
    fun onlyExpandedWindowsShowTwoPanes() {
        assertThat(
            OfPaneLayout.of(OfWindowClass.EXPANDED, hasSelection = false),
        ).isEqualTo(OfPaneLayout.LIST_AND_DETAIL)
        assertThat(OfPaneLayout.of(OfWindowClass.EXPANDED, hasSelection = true)).isEqualTo(OfPaneLayout.LIST_AND_DETAIL)
    }

    @Test
    fun narrowerWindowsShowTheDetailOnlyWhenSomethingIsSelected() {
        for (windowClass in listOf(OfWindowClass.COMPACT, OfWindowClass.MEDIUM)) {
            assertThat(OfPaneLayout.of(windowClass, hasSelection = false)).isEqualTo(OfPaneLayout.LIST)
            assertThat(OfPaneLayout.of(windowClass, hasSelection = true)).isEqualTo(OfPaneLayout.DETAIL)
        }
    }
}
