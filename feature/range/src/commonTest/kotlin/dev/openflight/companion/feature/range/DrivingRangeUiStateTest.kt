// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ShotEvent
import kotlin.test.Test

/** The overlay's compact metrics while a ball flies (plan R7b). */
class DrivingRangeUiStateTest {
    @Test
    fun theMetricsFoldOnlyWhileTheBallFliesAndThroughTheLandingDwell() {
        assertThat(showing(RangePhase.Flying).compactMetrics).isTrue()
        assertThat(showing(RangePhase.Landed).compactMetrics).isTrue()

        assertThat(showing(RangePhase.Waiting).compactMetrics).isFalse()
        assertThat(showing(RangePhase.Preparing).compactMetrics).isFalse()
        assertThat(showing(RangePhase.Unavailable("no ball speed")).compactMetrics).isFalse()
        assertThat(DrivingRangeUiState.Ready().compactMetrics).isFalse()
    }

    @Test
    fun theDetailMetricsAreAStripInFlightAndADenseGridOverAPortraitSceneWhileWaiting() {
        // Every landscape, docked and large-text combination.
        val flags = listOf(false, true)
        val combinations = flags.flatMap { a -> flags.flatMap { b -> flags.map { c -> Triple(a, b, c) } } }
        for (phase in listOf(RangePhase.Flying, RangePhase.Landed)) {
            for ((landscape, docked, largeText) in combinations) {
                assertThat(showing(phase).detailLayout(landscape, docked, largeText))
                    .isEqualTo(RangeDetailLayout.STRIP)
            }
        }
        for (state in waitingStates()) {
            assertThat(state.detailLayout(landscape = false, docked = false, largeText = false))
                .isEqualTo(RangeDetailLayout.DENSE_GRID)
            assertThat(state.detailLayout(landscape = true, docked = false, largeText = false))
                .isEqualTo(RangeDetailLayout.ROW)
            assertThat(state.detailLayout(landscape = true, docked = true, largeText = false))
                .isEqualTo(RangeDetailLayout.GRID)
        }
        // Eight cells (the club selector and seven metrics): two rows of four, half the old four rows of two.
        assertThat(RangeDetailLayout.DENSE_GRID.columns).isEqualTo(4)
        assertThat(RangeDetailLayout.GRID.columns).isEqualTo(2)
    }

    /** Issue #80: at large text sizes the grids take half the columns, so values don't truncate. */
    @Test
    fun largeTextHalvesTheDetailColumns() {
        for (state in waitingStates()) {
            assertThat(state.detailLayout(landscape = false, docked = false, largeText = true))
                .isEqualTo(RangeDetailLayout.GRID)
            assertThat(state.detailLayout(landscape = true, docked = false, largeText = true))
                .isEqualTo(RangeDetailLayout.DENSE_GRID)
            assertThat(state.detailLayout(landscape = true, docked = true, largeText = true))
                .isEqualTo(RangeDetailLayout.GRID)
        }
    }

    private fun waitingStates() =
        listOf(DrivingRangeUiState.Ready(), showing(RangePhase.Waiting), showing(RangePhase.Preparing))

    @Test
    fun theStripSummarisesClubSpeedLaunchAndSpin() {
        assertThat(showing(RangePhase.Flying).compactMetricsSummary)
            .isEqualTo("Club 103.2 mph · Launch 12.6° · Spin 2,380 rpm")
    }

    @Test
    fun missingValuesShowADashWithoutAUnit() {
        val bare = shot.copy(clubSpeedMph = null, launchAngleVertical = null, spinRpm = null)

        assertThat(DrivingRangeUiState.Showing(bare, RangePhase.Flying, activeFlight = null).compactMetricsSummary)
            .isEqualTo("Club — · Launch — · Spin —")
    }

    private fun showing(phase: RangePhase) = DrivingRangeUiState.Showing(shot, phase, activeFlight = null)

    private companion object {
        val shot =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10",
                club = "driver",
                ballSpeedMph = 151.4,
                clubSpeedMph = 103.2,
                smashFactor = 1.47,
                estimatedCarryYards = 264.0,
                launchAngleVertical = 12.6,
                launchAngleHorizontal = -1.3,
                spinRpm = 2380.0,
                clubPathDeg = 2.1,
                spinAxisDeg = -3.4,
            )
    }
}
