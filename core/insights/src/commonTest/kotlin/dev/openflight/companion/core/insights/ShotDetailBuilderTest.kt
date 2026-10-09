// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.Conditions
import kotlin.test.Test

class ShotDetailBuilderTest {
    private val builder = ShotDetailBuilder()

    private val fade =
        ShotDetailInput(
            id = "a",
            shotNumber = 12,
            club = "7-iron",
            timestamp = "2026-07-29T19:42:10.123456",
            ballSpeedMph = 120.0,
            carryYards = 165.0,
            clubSpeedMph = 88.0,
            smashFactor = 1.36,
            launchAngleVertical = 16.2,
            launchAngleHorizontal = 2.6,
            spinRpm = 7000.0,
            spinAxisDeg = 6.3,
            spinSource = "measured",
            clubPathDeg = -1.4,
        )

    private fun build(
        input: ShotDetailInput = fade,
        units: UnitSystem = UnitSystem.IMPERIAL,
    ) = builder.build(input, Conditions.ISA, null, units)

    private fun ShotDetailState.metric(label: String) = metrics.first { it.label == label }

    @Test
    fun theTitleNamesTheShotAndTheClub() {
        val state = build()

        assertThat(state.title).isEqualTo("Shot 12 · 7-Iron")
        assertThat(state.subtitle).isEqualTo("2026-07-29 19:42")
    }

    @Test
    fun aPushedRightTiltedShotIsAPushFadeWithAFlight() {
        val state = build()

        assertThat(state.flight).isNotNull()
        assertThat(state.shapeLabel).isIn("Push fade", "Push slice")
        assertThat(state.shapeDescription).isNotNull().contains("Started right of target")
    }

    @Test
    fun anglesReadLeftOrRight() {
        val state = build()

        assertThat(state.metric("Direction").value).isEqualTo("R 2.6°")
        assertThat(state.metric("Spin axis").value).isEqualTo("R 6.3°")
        assertThat(state.metric("Club path").value).isEqualTo("L 1.4°")
        assertThat(state.metric("Launch").value).isEqualTo("16.2°")
    }

    @Test
    fun theCarryIsTheShotsAndTheTotalIsAnEstimate() {
        val state = build()

        assertThat(state.metric("Carry").value).isEqualTo("165")
        assertThat(state.metric("Total").estimated).isTrue()
        assertThat(state.metric("Offline").value).contains("R ")
    }

    @Test
    fun metricUnitsShowMetresAndKilometresPerHour() {
        val state = build(units = UnitSystem.METRIC)

        assertThat(state.metric("Carry").unit).isEqualTo("m")
        assertThat(state.metric("Carry").value).isEqualTo("151")
        assertThat(state.metric("Ball speed").unit).isEqualTo("km/h")
        assertThat(state.metric("Apex").unit).isEqualTo("m")
    }

    @Test
    fun anUnmeasuredSpinAxisIsShownMissingAndExplained() {
        val state = build(fade.copy(spinAxisDeg = null))

        assertThat(state.metric("Spin axis").value).isEqualTo("—")
        assertThat(state.notes).contains("Spin axis wasn't measured, so the flight is drawn without curve.")
    }

    @Test
    fun aCalculatedSpinIsBadgedAsAnEstimate() {
        assertThat(build(fade.copy(spinSource = "calculated")).metric("Spin").estimated).isTrue()
    }

    @Test
    fun aSwingSpeedRepHasNoFlight() {
        val state = build(fade.copy(swingSpeedMph = 95.0))

        assertThat(state.flight).isNull()
        assertThat(state.metrics.map { it.label }).isEqualTo(listOf("Swing speed"))
        assertThat(state.notes).contains("A swing-speed rep has no ball flight to draw.")
    }

    @Test
    fun theSummaryReadsTheShapeAndTheMeasuredValues() {
        val summary = build(fade.copy(clubPathDeg = null)).accessibilitySummary

        assertThat(summary).contains("Shot 12 · 7-Iron")
        assertThat(summary).contains("Carry 165 yds")
        assertThat(summary).doesNotContain("Club path")
    }

    @Test
    fun sidesRoundOntoTheLine() {
        assertThat(formatSide(0.04, 1)).isEqualTo("0.0")
        assertThat(formatSide(-3.0, 0)).isEqualTo("L 3")
        assertThat(formatSide(6.26, 1)).isEqualTo("R 6.3")
    }
}
