// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class DispersionTest {
    @Test
    fun fewerThanThreeShotsHaveNoEllipse() {
        assertThat(computeDispersionEllipse(listOf(sample(0, 150), sample(5, 160)))).isNull()
    }

    @Test
    fun aSideToSideSpreadLiesAlongTheOfflineAxis() {
        // Sample variance 100 yd² offline, none in carry.
        val ellipse = computeDispersionEllipse(listOf(sample(-10, 150), sample(0, 150), sample(10, 150)))!!

        assertThat(ellipse.centerOfflineYards).isCloseTo(0.0, TOLERANCE)
        assertThat(ellipse.centerCarryYards).isCloseTo(150.0, TOLERANCE)
        assertThat(ellipse.semiMajorYards).isCloseTo(15.096, TOLERANCE)
        assertThat(ellipse.semiMinorYards).isCloseTo(0.0, TOLERANCE)
        assertThat(ellipse.rotationDegrees).isCloseTo(0.0, TOLERANCE)
    }

    @Test
    fun aLongShortSpreadLiesAlongTheCarryAxis() {
        val ellipse = computeDispersionEllipse(listOf(sample(0, 140), sample(0, 150), sample(0, 160)))!!

        assertThat(ellipse.semiMajorYards).isCloseTo(15.096, TOLERANCE)
        assertThat(ellipse.rotationDegrees).isCloseTo(90.0, TOLERANCE)
        assertThat(ellipse.halfExtentOfflineYards).isCloseTo(0.0, TOLERANCE)
        assertThat(ellipse.halfExtentCarryYards).isCloseTo(15.096, TOLERANCE)
    }

    @Test
    fun longAndRightShotsTiltTheEllipseByFortyFiveDegrees() {
        // Variances 100 and covariance 100: one eigenvalue of 200 along the diagonal.
        val ellipse = computeDispersionEllipse(listOf(sample(-10, 140), sample(0, 150), sample(10, 160)))!!

        assertThat(ellipse.rotationDegrees).isCloseTo(45.0, TOLERANCE)
        assertThat(ellipse.semiMajorYards).isCloseTo(21.349, TOLERANCE)
        assertThat(ellipse.semiMinorYards).isCloseTo(0.0, TOLERANCE)
        assertThat(ellipse.halfExtentOfflineYards).isCloseTo(15.096, TOLERANCE)
    }

    @Test
    fun identicalShotsCollapseToAPoint() {
        val ellipse = computeDispersionEllipse(List(4) { sample(3, 200) })!!

        assertThat(ellipse.semiMajorYards).isCloseTo(0.0, TOLERANCE)
        assertThat(ellipse.semiMinorYards).isCloseTo(0.0, TOLERANCE)
    }

    @Test
    fun noShotsHaveNoViewport() {
        assertThat(computeDispersionViewport(emptyList())).isNull()
    }

    @Test
    fun theViewportPadsAndRoundsToTidyYardagesWithArcsEveryFifty() {
        val viewport = computeDispersionViewport(listOf(sample(-12, 148), sample(8, 203)))

        assertThat(viewport).isEqualTo(
            DispersionViewport(
                minCarryYards = 130.0,
                maxCarryYards = 220.0,
                halfWidthYards = 25.0,
                arcs = listOf(arc(150), arc(200)),
            ),
        )
    }

    @Test
    fun aNarrowCarryRangeGetsArcsEveryTwentyFiveAndTheMinimumWidth() {
        val viewport = computeDispersionViewport(listOf(sample(0, 150), sample(3, 160)))

        assertThat(viewport).isEqualTo(
            DispersionViewport(
                minCarryYards = 140.0,
                maxCarryYards = 170.0,
                halfWidthYards = 20.0,
                arcs = listOf(arc(150)),
            ),
        )
    }

    @Test
    fun theViewportGrowsToHoldEveryEllipse() {
        val ellipse = DispersionEllipse(0.0, 150.0, semiMajorYards = 40.0, semiMinorYards = 5.0, rotationDegrees = 0.0)

        val viewport = computeDispersionViewport(listOf(sample(0, 150)), listOf(ellipse))!!

        assertThat(viewport.halfWidthYards).isEqualTo(50.0)
    }

    @Test
    fun theViewportNeverStartsBehindTheTee() {
        val viewport = computeDispersionViewport(listOf(sample(0, 5), sample(0, 30)))!!

        assertThat(viewport.minCarryYards).isEqualTo(0.0)
        assertThat(viewport.arcs).isEqualTo(listOf(arc(25)))
    }

    @Test
    fun theProjectionFillsTheHeightAndStretchesOfflineUpToThreeTimes() {
        // Carry: 200 px for 100 yd = 2 px/yd. Offline could fit 4 px/yd (200 px for 50 yd) and may use it.
        val projection = DispersionProjection(VIEWPORT, width = 200.0, height = 200.0)

        assertThat(projection.yScale).isEqualTo(2.0)
        assertThat(projection.xScale).isEqualTo(4.0)
        assertThat(projection.x(0.0)).isEqualTo(100.0)
        assertThat(projection.x(10.0)).isEqualTo(140.0)
        assertThat(projection.y(200.0)).isEqualTo(0.0)
        assertThat(projection.y(100.0)).isEqualTo(200.0)
        assertThat(projection.teeY).isEqualTo(400.0)
    }

    @Test
    fun theStretchIsCapped() {
        val projection = DispersionProjection(VIEWPORT, width = 1000.0, height = 200.0)

        assertThat(projection.xScale).isEqualTo(6.0)
    }

    @Test
    fun aWideCanvasNeverSquashesOfflineBelowCarry() {
        // 50 yd across 100 px = 2 px/yd; carry could have 4 px/yd but keeps to 2.
        val projection = DispersionProjection(VIEWPORT, width = 100.0, height = 400.0)

        assertThat(projection.xScale).isEqualTo(2.0)
        assertThat(projection.yScale).isEqualTo(2.0)
    }

    @Test
    fun anArcIsCrossedOnItsFarSideAndMissesBeyondItsReach() {
        val projection = DispersionProjection(VIEWPORT, width = 200.0, height = 200.0)
        val arc = DispersionArc(radiusYards = 150.0, label = 150)

        assertThat(projection.arcY(arc, atX = projection.teeX)).isEqualTo(100.0)
        assertThat(projection.arcY(arc, atX = projection.teeX + 700.0)).isNull()
    }

    @Test
    fun anEllipseOutlineFollowsItsRotationThroughTheStretch() {
        val projection = DispersionProjection(VIEWPORT, width = 200.0, height = 200.0)
        val ellipse = DispersionEllipse(0.0, 150.0, semiMajorYards = 10.0, semiMinorYards = 5.0, rotationDegrees = 90.0)

        val outline = projection.ellipseOutline(ellipse, segments = 4)

        // Major axis along carry: 10 yd up is 20 px; minor axis sideways: 5 yd is 20 px.
        assertThat(outline[0].first).isCloseTo(100.0, TOLERANCE)
        assertThat(outline[0].second).isCloseTo(80.0, TOLERANCE)
        assertThat(outline[1].first).isCloseTo(80.0, TOLERANCE)
        assertThat(outline[1].second).isCloseTo(100.0, TOLERANCE)
    }

    @Test
    fun aTapPicksTheClosestShotWithinTheRadius() {
        val projection = DispersionProjection(VIEWPORT, width = 200.0, height = 200.0)
        val samples = listOf(sample(0, 150), sample(10, 150))

        // Drawn at x = 100 and x = 140.
        assertThat(projection.nearest(samples, tapX = 104.0, tapY = 100.0, radius = 10.0)).isNotNull().isEqualTo(0)
        assertThat(projection.nearest(samples, tapX = 136.0, tapY = 101.0, radius = 10.0)).isNotNull().isEqualTo(1)
        assertThat(projection.nearest(samples, tapX = 160.0, tapY = 100.0, radius = 10.0)).isNull()
    }

    @Test
    fun metricArcsSitAtRoundMetres() {
        // 130..220 yd is 118.9..201.2 m: arcs at 150 m and 200 m.
        val viewport = computeDispersionViewport(listOf(sample(-12, 148), sample(8, 203)), unitsPerYard = 0.9144)!!

        assertThat(viewport.arcs.map { it.label }).isEqualTo(listOf(150, 200))
        assertThat(viewport.arcs[0].radiusYards).isCloseTo(164.042, TOLERANCE)
    }

    @Test
    fun aShotFarOutsideItsClubIsALikelyBadRead() {
        val samples =
            listOf(sample(-2, 160), sample(1, 164), sample(3, 158), sample(0, 162), sample(-1, 166), sample(2, 231))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(setOf(5))
    }

    @Test
    fun aWideButOrdinaryScatterHasNoBadReads() {
        val samples =
            listOf(sample(-9, 150), sample(8, 172), sample(2, 158), sample(-4, 166), sample(6, 154), sample(-1, 162))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(emptySet())
    }

    @Test
    fun aVeryTightGroupDoesNotFlagASmallMiss() {
        // Carry within a yard, then one 5 yd long: well inside the 3 yd minimum spread.
        val samples = listOf(sample(0, 160), sample(0, 161), sample(0, 160), sample(0, 161), sample(0, 165))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(emptySet())
    }

    @Test
    fun shotsAlongALineAreNotFlaggedForSittingOffIt() {
        // Five drivers that happen to line up diagonally, then one off that line: an ordinary scatter.
        val samples = listOf(sample(-4, 249), sample(2, 258), sample(4, 266), sample(6, 271), sample(7, 255))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(emptySet())
    }

    @Test
    fun aShotFarOffLineIsFlaggedOnItsSide() {
        val samples =
            listOf(sample(-2, 160), sample(1, 164), sample(3, 158), sample(0, 162), sample(-1, 166), sample(35, 161))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(setOf(5))
    }

    @Test
    fun aShotWithoutSideDataIsJudgedOnCarryAlone() {
        // Offline 40 would be far out, but it wasn't measured; the carry is normal.
        val samples = listOf(sample(0, 160), sample(1, 163), sample(-1, 158), sample(2, 161), sample(40, 162))
        val measured = listOf(true, true, true, true, false)

        assertThat(findDispersionOutliers(samples, measured)).isEqualTo(emptySet())
    }

    @Test
    fun fewerThanFiveShotsAreNeverFlagged() {
        val samples = listOf(sample(0, 160), sample(1, 162), sample(-1, 161), sample(0, 240))

        assertThat(findDispersionOutliers(samples, List(samples.size) { true })).isEqualTo(emptySet())
    }

    private fun arc(yards: Int) = DispersionArc(radiusYards = yards.toDouble(), label = yards)

    private fun sample(
        offline: Int,
        carry: Int,
    ) = DispersionSample(offline.toDouble(), carry.toDouble())

    private companion object {
        const val TOLERANCE = 0.001
        val VIEWPORT =
            DispersionViewport(
                minCarryYards = 100.0,
                maxCarryYards = 200.0,
                halfWidthYards = 25.0,
                arcs = listOf(DispersionArc(radiusYards = 150.0, label = 150)),
            )
    }
}
