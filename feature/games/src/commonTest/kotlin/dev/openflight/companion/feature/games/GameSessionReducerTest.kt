// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class GameSessionReducerTest {
    private val ann = Player("ann", "Ann", 0)
    private val bob = Player("bob", "Bob", 1)

    private fun shot(
        id: String,
        carry: Double,
        offline: Double? = 0.0,
    ) = GameShot(eventId = id, club = "pw", carryYards = carry, offlineYards = offline)

    private fun callout(
        players: List<Player> = listOf(ann, bob),
        shots: Int = 2,
        perTurn: Int = 1,
    ) = GameConfig(GameMode.TargetCallout(List(shots) { 80.0 }), players, TurnOrder(perTurn))

    private fun GameState.on(vararg events: GameEvent) = events.fold(this, GameSessionReducer::reduce)

    private fun started(config: GameConfig) = GameState.new(config).on(GameEvent.Start(1_000))

    @Test
    fun nothingCountsBeforeStart() {
        val state = GameState.new(callout()).on(GameEvent.ShotSighted("a"), GameEvent.ShotFinal(shot("a", 83.0)))
        assertThat(state.attributions).isEmpty()
        assertThat(state.currentPlayer).isNull()
    }

    @Test
    fun turnsAlternateRoundRobinOnSighting() {
        val state = started(callout())
        assertThat(state.currentPlayer).isEqualTo(ann)
        assertThat(state.startedAtEpochMillis).isEqualTo(1_000L)

        val afterAnn = state.on(GameEvent.ShotSighted("a"))
        assertThat(afterAnn.currentPlayer).isEqualTo(bob)
        val afterBob = afterAnn.on(GameEvent.ShotSighted("b"))
        assertThat(afterBob.currentPlayer).isEqualTo(ann)
        assertThat(afterBob.attributions.map { it.playerId }).containsExactly("ann", "bob")
    }

    @Test
    fun shotsPerTurnKeepThePlayerUpForTheirWholeTurn() {
        var state = started(callout(shots = 4, perTurn = 2))
        val order = mutableListOf<String?>()
        repeat(8) { index ->
            order += state.currentPlayer?.id
            state = state.on(GameEvent.ShotSighted("s$index"))
        }
        assertThat(order).containsExactly("ann", "ann", "bob", "bob", "ann", "ann", "bob", "bob")
        assertThat(state.turn).isNull()
    }

    @Test
    fun aSwingBelongsToWhoeverWasUpWhenItWasFirstSighted() {
        // Ann swings (provisional), Bob swings before Ann's final arrives: A14.
        val state =
            started(callout()).on(
                GameEvent.ShotSighted("a"),
                GameEvent.ShotSighted("b"),
                GameEvent.ShotFinal(shot("b", 90.0)),
                GameEvent.ShotFinal(shot("a", 83.0)),
            )
        assertThat(state.scoresOf("ann").map { it.deltaYards }).containsExactly(3.0)
        assertThat(state.scoresOf("bob").map { it.deltaYards }).containsExactly(10.0)
    }

    @Test
    fun aV1FinalWithoutASightingIsAttributedAndScoredAtOnce() {
        val state = started(callout()).on(GameEvent.ShotFinal(shot("a", 77.0)), GameEvent.ShotSighted("a"))
        assertThat(state.attributions.size).isEqualTo(1)
        assertThat(state.scoresOf("ann").single().deltaYards).isEqualTo(-3.0)
        assertThat(state.currentPlayer).isEqualTo(bob)
    }

    @Test
    fun duplicateSightingsAndFinalsCountOnce() {
        val state =
            started(callout()).on(
                GameEvent.ShotSighted("a"),
                GameEvent.ShotSighted("a"),
                GameEvent.ShotFinal(shot("a", 83.0)),
                GameEvent.ShotFinal(shot("a", 99.0)),
            )
        assertThat(state.attributions.size).isEqualTo(1)
        assertThat(state.scoresOf("ann").single().deltaYards).isEqualTo(3.0)
    }

    @Test
    fun whilePausedNewSwingsAreIgnoredButSightedOnesStillScore() {
        val state =
            started(callout()).on(
                GameEvent.ShotSighted("a"),
                GameEvent.Pause,
                GameEvent.ShotSighted("b"),
                GameEvent.ShotFinal(shot("b", 80.0)),
                GameEvent.ShotFinal(shot("a", 81.0)),
            )
        assertThat(state.status).isEqualTo(GameStatus.PAUSED)
        assertThat(state.attributions.map { it.eventId }).containsExactly("a")
        assertThat(state.scoresOf("ann").single().deltaYards).isEqualTo(1.0)
        assertThat(state.on(GameEvent.Resume).status).isEqualTo(GameStatus.IN_PROGRESS)
    }

    @Test
    fun undoGivesTheTurnBackAndIgnoresTheUndoneSwingsFinal() {
        val state =
            started(callout()).on(
                GameEvent.ShotSighted("a"),
                GameEvent.ShotSighted("b"),
                GameEvent.Undo,
            )
        assertThat(state.currentPlayer).isEqualTo(bob)
        assertThat(state.attributions.map { it.eventId }).containsExactly("a")

        val later = state.on(GameEvent.ShotFinal(shot("b", 80.0)), GameEvent.ShotSighted("b2"))
        assertThat(later.attributions.map { it.eventId to it.playerId }).containsExactly("a" to "ann", "b2" to "bob")
    }

    @Test
    fun undoReopensACompleteGame() {
        val complete =
            started(callout(players = listOf(ann), shots = 1)).on(GameEvent.ShotFinal(shot("a", 80.0)))
        assertThat(complete.status).isEqualTo(GameStatus.COMPLETE)
        val reopened = complete.on(GameEvent.Undo)
        assertThat(reopened.status).isEqualTo(GameStatus.IN_PROGRESS)
        assertThat(reopened.currentPlayer).isEqualTo(ann)
    }

    @Test
    fun skipPassesTheTurnWithoutUsingAShot() {
        val state = started(callout()).on(GameEvent.Skip)
        assertThat(state.currentPlayer).isEqualTo(bob)
        assertThat(state.attributions).isEmpty()
        // A player whose shots are used up is passed over.
        val annDone =
            started(callout(shots = 1)).on(GameEvent.ShotSighted("a"), GameEvent.Skip)
        assertThat(annDone.currentPlayer).isEqualTo(bob)
    }

    @Test
    fun theGameCompletesOnceEveryShotIsScoredAndEndsOnEnd() {
        val events =
            listOf(
                GameEvent.Start(5),
                GameEvent.ShotSighted("a1"),
                GameEvent.ShotSighted("b1"),
                GameEvent.ShotSighted("a2"),
                GameEvent.ShotSighted("b2"),
                GameEvent.ShotFinal(shot("a1", 82.0)),
                GameEvent.ShotFinal(shot("b1", 79.0)),
                GameEvent.ShotFinal(shot("a2", 80.0)),
            )
        val almost = GameSessionReducer.replay(callout(), events)
        assertThat(almost.status).isEqualTo(GameStatus.IN_PROGRESS)
        assertThat(almost.turn).isNull()

        val complete = almost.on(GameEvent.ShotFinal(shot("b2", 84.0)))
        assertThat(complete.status).isEqualTo(GameStatus.COMPLETE)
        // Ann averages 1.0, Bob 2.5: lower wins.
        assertThat(complete.winners).containsExactly(ann)

        val ended = complete.on(GameEvent.End(9))
        assertThat(ended.status).isEqualTo(GameStatus.ENDED)
        assertThat(ended.endedAtEpochMillis).isEqualTo(9L)
        assertThat(ended.on(GameEvent.ShotSighted("x")).attributions.size).isEqualTo(4)
    }

    @Test
    fun equalTotalsAreATie() {
        val state =
            started(callout(shots = 1)).on(
                GameEvent.ShotFinal(shot("a", 83.0)),
                GameEvent.ShotFinal(shot("b", 77.0)),
            )
        assertThat(state.winners).containsExactly(ann, bob)
        assertThat(started(callout()).winners).isEmpty()
    }

    @Test
    fun higherWinsForBullseyeAndClosestToPinTakesTheBestShot() {
        val bullseye = GameConfig(GameMode.Bullseye(100.0, shotsPerPlayer = 1), listOf(ann, bob))
        val b =
            started(bullseye).on(GameEvent.ShotFinal(shot("a", 108.0)), GameEvent.ShotFinal(shot("b", 101.0)))
        assertThat(b.winners).containsExactly(bob)

        val ctp = GameConfig(GameMode.ClosestToPin(150.0, shotsPerPlayer = 2), listOf(ann, bob))
        val c =
            started(ctp).on(
                GameEvent.ShotFinal(shot("a1", 170.0)),
                GameEvent.ShotFinal(shot("b1", 146.0)),
                GameEvent.ShotFinal(shot("a2", 151.0)),
                GameEvent.ShotFinal(shot("b2", 130.0)),
            )
        assertThat(c.standings.map { it.total }).containsExactly(1.0, 4.0)
        assertThat(c.winners).containsExactly(ann)
    }

    @Test
    fun golfPongEndsAsSoonAsSomeoneSinksEveryCup() {
        val pong = GameConfig(GameMode.GolfPong(listOf(PongCup(50.0), PongCup(100.0))), listOf(ann, bob))
        val state =
            started(pong).on(
                GameEvent.ShotFinal(shot("a1", 51.0)),
                GameEvent.ShotFinal(shot("b1", 75.0)),
                GameEvent.ShotSighted("a2"),
                GameEvent.ShotSighted("b2"),
                GameEvent.ShotFinal(shot("a2", 99.0)),
                GameEvent.ShotFinal(shot("b2", 50.0)),
            )
        assertThat(state.status).isEqualTo(GameStatus.COMPLETE)
        assertThat(state.winners).containsExactly(ann)
        // Bob's swing was already in the air, but the game is over.
        assertThat(state.attributions.first { it.eventId == "b2" }.score).isNull()
        assertThat(
            state.attributions
                .first { it.eventId == "a2" }
                .score
                ?.sunkCupIndex,
        ).isNotNull()
    }

    @Test
    fun theTargetIsFixedAtTheSighting() {
        val ladder = GameConfig(GameMode.TargetCallout.of(TargetPlan.Ladder()), listOf(ann))
        val state = started(ladder).on(GameEvent.ShotSighted("a"), GameEvent.ShotSighted("b"))
        assertThat(state.attributions.map { it.target.distanceYards }).containsExactly(60.0, 70.0)
        assertThat(state.currentTarget).isEqualTo(GameTarget(80.0))
    }
}
