// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.openflight.companion.core.flight.Curve
import dev.openflight.companion.core.flight.ShotShape
import kotlin.test.Test

class ShotShapeSummaryTest {
    private fun shapes(vararg curves: Curve?) = curves.map { ShotShape(start = null, curve = it) }

    @Test
    fun oneCurveOnHalfTheShotsIsMostlyThatCurve() {
        val summary = summarizeShapes(shapes(Curve.FADE, Curve.FADE, Curve.STRAIGHT, Curve.DRAW))

        assertThat(summary?.headline).isEqualTo("Mostly fade")
        assertThat(summary?.talliesLabel).isEqualTo("Draw 1 · Straight 1 · Fade 2")
    }

    @Test
    fun aSideWithoutOneCurveOnHalfLeans() {
        val summary = summarizeShapes(shapes(Curve.DRAW, Curve.DRAW, Curve.HOOK, Curve.STRAIGHT, Curve.FADE))

        assertThat(summary?.headline).isEqualTo("Leans left")
    }

    @Test
    fun noPatternIsMixed() {
        val summary = summarizeShapes(shapes(Curve.DRAW, Curve.STRAIGHT, Curve.FADE, Curve.SLICE, Curve.HOOK))

        assertThat(summary?.headline).isEqualTo("Mixed")
    }

    @Test
    fun underThreeShotsNamesTheShapeWithoutATrend() {
        assertThat(summarizeShapes(shapes(Curve.SLICE))?.headline).isEqualTo("Slice")
    }

    @Test
    fun unmeasuredCurvesAreLeftOut() {
        val summary = summarizeShapes(shapes(null, null, Curve.DRAW, Curve.DRAW, Curve.DRAW))

        assertThat(summary).isNotNull()
        assertThat(summary?.measuredCount).isEqualTo(3)
        assertThat(summarizeShapes(shapes(null, null))).isNull()
    }
}
