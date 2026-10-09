// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class ShotShapeTest {
    @Test
    fun theLabelNamesTheStartThenTheCurve() {
        assertThat(ShotShape(StartLine.PUSH, Curve.FADE).label).isEqualTo("Push fade")
        assertThat(ShotShape(StartLine.PULL, Curve.HOOK).label).isEqualTo("Pull hook")
        assertThat(ShotShape(StartLine.STRAIGHT, Curve.DRAW).label).isEqualTo("Draw")
        assertThat(ShotShape(StartLine.PULL, Curve.STRAIGHT).label).isEqualTo("Pull")
        assertThat(ShotShape(StartLine.STRAIGHT, Curve.STRAIGHT).label).isEqualTo("Straight")
    }

    @Test
    fun aMissingPartIsLeftOutOfTheLabel() {
        assertThat(ShotShape(null, Curve.SLICE).label).isEqualTo("Slice")
        assertThat(ShotShape(StartLine.PUSH, null).label).isEqualTo("Push")
        assertThat(ShotShape(null, null).label).isNull()
        assertThat(ShotShape(StartLine.STRAIGHT, null).label).isNull()
    }

    @Test
    fun theDescriptionSaysWhatTheBallDid() {
        assertThat(ShotShape(StartLine.PUSH, Curve.FADE).description)
            .isEqualTo("Started right of target, curved right.")
        assertThat(ShotShape(null, Curve.HOOK).description).isEqualTo("Curved hard left.")
        assertThat(ShotShape(null, null).description).isNull()
    }

    @Test
    fun theStartLineThresholdIsTwoDegreesEitherSide() {
        assertThat(ShotShape.startLine(-2.0)).isEqualTo(StartLine.PULL)
        assertThat(ShotShape.startLine(-1.9)).isEqualTo(StartLine.STRAIGHT)
        assertThat(ShotShape.startLine(1.9)).isEqualTo(StartLine.STRAIGHT)
        assertThat(ShotShape.startLine(2.0)).isEqualTo(StartLine.PUSH)
    }

    @Test
    fun theCurveGrowsFromStraightToHookOrSlice() {
        assertThat(ShotShape.curve(0.0)).isEqualTo(Curve.STRAIGHT)
        assertThat(ShotShape.curve(0.024)).isEqualTo(Curve.STRAIGHT)
        assertThat(ShotShape.curve(0.025)).isEqualTo(Curve.FADE)
        assertThat(ShotShape.curve(0.08)).isEqualTo(Curve.SLICE)
        assertThat(ShotShape.curve(-0.025)).isEqualTo(Curve.DRAW)
        assertThat(ShotShape.curve(-0.08)).isEqualTo(Curve.HOOK)
    }
}
