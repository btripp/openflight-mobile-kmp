// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.GolfClub
import kotlin.random.Random
import kotlin.test.Test

class GameModeTest {
    private fun shot(
        carry: Double,
        offline: Double? = 0.0,
        total: Double? = null,
        apex: Double? = null,
        carryEstimated: Boolean = false,
    ) = GameShot(
        eventId = "e",
        club = "7-iron",
        carryYards = carry,
        carryEstimated = carryEstimated,
        totalYards = total,
        offlineYards = offline,
        apexYards = apex,
    )

    private data class Case(
        val name: String,
        val shot: GameShot,
        val points: Double,
        val delta: Double?,
        val lateralUnknown: Boolean = false,
        val estimated: Boolean = false,
    )

    private fun GameMode.check(cases: List<Case>) {
        for (case in cases) {
            val score = score(case.shot, target(0, emptyList()), emptyList())
            assertThat(score.points, case.name).isCloseTo(case.points, 0.01)
            val delta = case.delta
            if (delta ==
                null
            ) {
                assertThat(score.deltaYards, case.name).isNull()
            } else {
                assertThat(score.deltaYards!!, case.name).isCloseTo(delta, 0.01)
            }
            assertThat(score.lateralUnknown, case.name).isEqualTo(case.lateralUnknown)
            assertThat(score.metricEstimated, case.name).isEqualTo(case.estimated)
        }
    }

    @Test
    fun targetCalloutScoresTheSignedCarryMissAndTotalWhenAsked() {
        GameMode.TargetCallout(listOf(80.0)).check(
            listOf(
                Case("3 long", shot(83.0), points = 3.0, delta = 3.0),
                Case("4.5 short", shot(75.5), points = 4.5, delta = -4.5),
                Case("dead on", shot(80.0), points = 0.0, delta = 0.0),
                Case("side doesn't matter", shot(80.0, offline = 20.0), points = 0.0, delta = 0.0),
                Case(
                    "adjusted carry is est.",
                    shot(82.0, carryEstimated = true),
                    points = 2.0,
                    delta = 2.0,
                    estimated = true,
                ),
            ),
        )
        GameMode.TargetCallout(listOf(100.0), DistanceMetric.TOTAL).check(
            listOf(
                Case("total (est.)", shot(92.0, total = 104.0), points = 4.0, delta = 4.0, estimated = true),
                Case("no total falls back to carry", shot(92.0, total = null), points = 8.0, delta = -8.0),
            ),
        )
    }

    @Test
    fun targetPlansGiveFixedRandomAndLadderSequences() {
        assertThat(TargetPlan.Fixed(80.0, 3).targets(Random(1))).containsExactly(80.0, 80.0, 80.0)
        assertThat(TargetPlan.Ladder().targets(Random(1)))
            .containsExactly(60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0, 140.0)
        val random = TargetPlan.RandomSequence(50.0, 150.0, shots = 20).targets(Random(42))
        assertThat(random.size).isEqualTo(20)
        assertThat(random.all { it in 50.0..150.0 && it % 5.0 == 0.0 }).isTrue()
        // Deterministic for a seed, so a replay gives the same targets.
        assertThat(TargetPlan.RandomSequence(50.0, 150.0, shots = 20).targets(Random(42))).isEqualTo(random)
        val ladder = GameMode.TargetCallout.of(TargetPlan.Ladder())
        assertThat(ladder.shotsPerPlayer).isEqualTo(9)
        assertThat(ladder.target(2, emptyList())).isEqualTo(GameTarget(80.0))
    }

    @Test
    fun closestToPinScoresTheStraightLineMissOrDistanceOnlyWithoutASide() {
        GameMode.ClosestToPin(pinYards = 150.0, pinLateralYards = 4.0).check(
            listOf(
                Case("3-4-5", shot(153.0, offline = 8.0), points = 5.0, delta = 5.0),
                Case("in the hole", shot(150.0, offline = 4.0), points = 0.0, delta = 0.0),
                Case(
                    "no horizontal angle",
                    shot(146.0, offline = null),
                    points = 4.0,
                    delta = 4.0,
                    lateralUnknown = true,
                ),
            ),
        )
    }

    @Test
    fun bullseyeRingsGive10_5_3_1AndNothingOutside() {
        GameMode.Bullseye(targetYards = 100.0).check(
            listOf(
                Case("inside 5", shot(103.0, offline = 4.0), points = 10.0, delta = 5.0),
                Case("inside 10", shot(108.0), points = 5.0, delta = 8.0),
                Case("inside 20", shot(88.0, offline = 9.0), points = 3.0, delta = 15.0),
                Case("inside 30", shot(125.0), points = 1.0, delta = 25.0),
                Case("outside", shot(131.0), points = 0.0, delta = 31.0),
                Case("distance only", shot(96.0, offline = null), points = 10.0, delta = 4.0, lateralUnknown = true),
            ),
        )
    }

    @Test
    fun golfPongSinksTheNearestStandingCupWithinItsRadius() {
        val pong = GameMode.GolfPong.standard()
        assertThat(pong.cups.map { it.distanceYards }).containsExactly(50.0, 70.0, 90.0, 110.0, 130.0, 150.0)
        assertThat(
            GameMode.GolfPong
                .standard(cupCount = 10)
                .cups.size,
        ).isEqualTo(10)

        val hit = pong.score(shot(92.0, offline = 3.0), pong.target(0, emptyList()), emptyList())
        assertThat(hit.sunkCupIndex).isEqualTo(2)
        assertThat(hit.points).isEqualTo(1.0)

        val miss = pong.score(shot(100.0), pong.target(0, emptyList()), emptyList())
        assertThat(miss.sunkCupIndex).isNull()
        assertThat(miss.points).isEqualTo(0.0)

        // A sunk cup can't be sunk again, so the next hit there misses.
        val again = pong.score(shot(90.0), pong.target(1, listOf(hit)), listOf(hit))
        assertThat(again.sunkCupIndex).isNull()
        assertThat(again.deltaYards!!).isCloseTo(20.0, 0.01)
        // The call-out target is the nearest standing cup.
        assertThat(pong.target(1, listOf(ShotScore(1.0, 0.0, sunkCupIndex = 0)))).isEqualTo(GameTarget(70.0))
    }

    @Test
    fun golfPongIsWonWhenEveryCupIsSunk() {
        val pong = GameMode.GolfPong(listOf(PongCup(50.0), PongCup(100.0)))
        val first = ShotScore(1.0, 0.0, sunkCupIndex = 0)
        assertThat(pong.hasWon(listOf(first))).isFalse()
        assertThat(pong.hasWon(listOf(first, ShotScore(0.0, 9.0), ShotScore(1.0, 1.0, sunkCupIndex = 1)))).isTrue()
    }

    @Test
    fun iconicShotsWeighCarryCurveAndApex() {
        val target =
            IconicShot("t", "Test", "", GolfClub.DRIVER, targetCarryYards = 250.0, curveYards = 12.0, apexYards = 30.0)
        val mode = GameMode.IconicShots(target)
        mode.check(
            listOf(
                Case(
                    "perfect",
                    shot(250.0, offline = 12.0, apex = 30.0),
                    points = 100.0,
                    delta = 0.0,
                    estimated = true,
                ),
                // carry 10 off (0.5 × 0.5) + curve on (0.3) + apex on (0.2) = 0.75.
                Case(
                    "carry 10 short",
                    shot(240.0, offline = 12.0, apex = 30.0),
                    points = 75.0,
                    delta = -10.0,
                    estimated = true,
                ),
                // No side: carry (0.5) and apex (0.2) are re-weighted: (0.5 × 1 + 0.2 × 0.5) / 0.7.
                Case(
                    "no side",
                    shot(250.0, offline = null, apex = 37.5),
                    points = 86.0,
                    delta = 0.0,
                    lateralUnknown = true,
                    estimated = true,
                ),
                Case(
                    "way off",
                    shot(200.0, offline = -30.0, apex = 60.0),
                    points = 0.0,
                    delta = -50.0,
                    estimated = true,
                ),
            ),
        )
        // Without an apex in the entry, carry and curve decide: (0.5 × 1 + 0.3 × 0) / 0.8.
        val flat = GameMode.IconicShots(target.copy(apexYards = null))
        assertThat(
            flat.score(shot(250.0, offline = 30.0), flat.target(0, emptyList()), emptyList()).points,
        ).isEqualTo(63.0)
    }

    @Test
    fun theIconicCatalogIsGenericAndWellFormed() {
        val shots = IconicShotCatalog.shots
        assertThat(shots).isNotEmpty()
        assertThat(shots.map { it.id }.distinct().size).isEqualTo(shots.size)
        assertThat(
            shots.all { it.title.isNotBlank() && it.description.isNotBlank() && it.targetCarryYards > 0 },
        ).isTrue()
        assertThat(IconicShotCatalog.find("fade-over-water")?.club).isEqualTo(GolfClub.DRIVER)
        assertThat(IconicShotCatalog.find("nope")).isNull()
    }

    @Test
    fun totalsAverageBestOrSumByMode() {
        val scores = listOf(ShotScore(3.0, 3.0), ShotScore(1.0, -1.0), ShotScore(5.0, 5.0))
        assertThat(GameMode.TargetCallout(listOf(80.0)).total(scores)).isEqualTo(3.0)
        assertThat(GameMode.ClosestToPin(100.0).total(scores)).isEqualTo(1.0)
        assertThat(GameMode.Bullseye(100.0).total(scores)).isEqualTo(9.0)
        assertThat(GameMode.IconicShots(IconicShotCatalog.shots.first()).total(scores)).isEqualTo(5.0)
        assertThat(GameMode.Bullseye(100.0).total(emptyList())).isNull()
    }
}
