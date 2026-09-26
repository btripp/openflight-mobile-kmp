// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import assertk.assertions.single
import dev.openflight.companion.core.data.DefaultActiveGameRepository
import dev.openflight.companion.core.model.CalloutContext
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakeActivityRepository
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeFinalShotStream
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GamesViewModelTest {
    private val shots = FakeShotRepository()
    private val finalShots = FakeFinalShotStream()
    private val piSession = FakePiSessionRepository()
    private val activeGame = DefaultActiveGameRepository()
    private val activities = FakeActivityRepository()
    private val history = FakeShotHistoryRepository()
    private val conditions = FakeConditionsRepository()
    private val settings = FakeSettingsRepository()
    private var clock = 1_000L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(launch: GameLaunch? = null) =
        GamesViewModel(
            shots = shots,
            finalShots = finalShots,
            piSession = piSession,
            activeGame = activeGame,
            activities = activities,
            history = history,
            conditions = conditions,
            settings = settings,
            launch = launch,
            now = { clock },
            random = Random(7),
            newId = { "activity-1" },
        )

    /** Keeps [GamesViewModel.uiState] hot so `.value` is current. */
    private fun TestScope.observe(viewModel: GamesViewModel): GamesViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun shot(
        n: Int,
        carry: Double,
    ) = ShotEvent(
        schemaVersion = 1,
        eventId = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}",
        timestamp = "2026-09-25T10:00:0$n",
        club = "pw",
        ballSpeedMph = 90.0,
        estimatedCarryYards = carry,
    )

    private suspend fun swing(shot: ShotEvent) {
        finalShots.emitFirstSighting(shot)
        finalShots.emitFinal(shot)
    }

    private fun GamesViewModel.playing() = uiState.value as GamesUiState.Playing

    @Test
    fun withoutAConnectionItAsksToConnectAndWontStart() =
        runTest {
            val viewModel = observe(viewModel())

            assertThat(viewModel.uiState.value).isInstanceOf<GamesUiState.ConnectToPlay>()
            viewModel.onEvent(GamesEvent.Start)
            assertThat(viewModel.uiState.value).isInstanceOf<GamesUiState.ConnectToPlay>()
            assertThat(activeGame.activeGame.value).isNull()
        }

    @Test
    fun theLaunchArgumentOpensTheModeAtItsDistance() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel(GameLaunch.fromRoute("CLOSEST_TO_PIN", 152.0)))

            val setup = (viewModel.uiState.value as GamesUiState.Setup).setup
            assertThat(setup.type).isEqualTo(GameType.CLOSEST_TO_PIN)
            assertThat(setup.targetYards).isEqualTo(152.0)
            assertThat(GameLaunch.fromRoute("nonsense", -3.0)).isEqualTo(GameLaunch())
        }

    @Test
    fun shotsAreAttributedToTheCurrentPlayerAndTheTurnAdvances() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.AddPlayer)
            viewModel.onEvent(GamesEvent.RenamePlayer("p1", " Ann "))
            viewModel.onEvent(GamesEvent.RenamePlayer("p2", "Bob"))
            viewModel.onEvent(GamesEvent.Start)

            assertThat(viewModel.playing().game.turnLabel).isEqualTo("Ann's turn")
            assertThat(activeGame.activeGame.value)
                .isEqualTo(CalloutContext(gameTitle = "Target call-out", targetYards = 80.0, playerName = "Ann"))

            swing(shot(1, 83.0))

            val game = viewModel.playing().game
            assertThat(game.currentPlayer?.name).isEqualTo("Bob")
            assertThat(game.lastShot?.player?.name).isEqualTo("Ann")
            assertThat(game.lastShot?.label).isEqualTo("3.0 yds long")
            assertThat(game.lastShot?.deltaYards).isEqualTo(3.0)
            assertThat(game.scoreboard.map { it.shotsLabel }).containsExactly("1 / 5", "0 / 5")
            assertThat(activeGame.activeGame.value?.playerName).isEqualTo("Bob")
        }

    @Test
    fun aSecondSwingInsideTheUpdateWindowCantStealTheFirst() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.AddPlayer)
            viewModel.onEvent(GamesEvent.Start)

            // Player 1's provisional, then player 2's swing, then player 1's final.
            finalShots.emitFirstSighting(shot(1, 79.0))
            finalShots.emitFirstSighting(shot(2, 90.0))
            finalShots.emitFinal(shot(2, 90.0))
            finalShots.emitFinal(shot(1, 79.0))

            val rows = viewModel.playing().game.scoreboard
            assertThat(rows.map { it.totalLabel }).containsExactly("1.0 yds", "10.0 yds")
        }

    @Test
    fun undoGivesTheShotBack() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.AddPlayer)
            viewModel.onEvent(GamesEvent.Start)
            swing(shot(1, 83.0))

            viewModel.onEvent(GamesEvent.Undo)

            val game = viewModel.playing().game
            assertThat(game.currentPlayer?.id).isEqualTo("p1")
            assertThat(game.lastShot).isNull()
            assertThat(game.canUndo).isFalse()
        }

    @Test
    fun endFilesTheGameWithItsHeadlinePlayersAndSessionAndClearsTheCallout() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            history.currentSessionId.value = "session-9"
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.SetShotsPerPlayer(2))
            viewModel.onEvent(GamesEvent.Start)
            swing(shot(1, 83.0))
            swing(shot(2, 74.6))
            assertThat(viewModel.playing().game.isComplete).isTrue()

            viewModel.effects.test {
                clock = 5_000L
                viewModel.onEvent(GamesEvent.End)
                assertThat(awaitItem()).isEqualTo(GamesEffect.Saved("activity-1"))
            }

            val results = viewModel.uiState.value as GamesUiState.Results
            assertThat(results.headline).isEqualTo("4.2 yds")
            assertThat(results.saved).isTrue()
            assertThat(activeGame.activeGame.value).isNull()

            assertThat(activities.state.value).single().given { activity ->
                assertThat(activity.type).isEqualTo("TARGET_CALLOUT")
                assertThat(activity.title).isEqualTo("Target call-out · 80 yds")
                assertThat(activity.headline).isEqualTo("4.2 yds")
                assertThat(activity.startedAtEpochMillis).isEqualTo(1_000L)
                assertThat(activity.endedAtEpochMillis).isEqualTo(5_000L)
                assertThat(activity.sessionId).isEqualTo("session-9")
                assertThat(GameRecords.players(activity).map { it.name }).containsExactly("Player 1")
                val result = GameRecords.result(activity)
                assertThat(result).isNotNull().prop(GameResultRecord::completed).isTrue()
                assertThat(result?.shots?.map { it.label }).isEqualTo(listOf("3.0 yds long", "5.4 yds short"))
            }

            viewModel.onEvent(GamesEvent.PlayAgain)
            assertThat(viewModel.uiState.value).isInstanceOf<GamesUiState.Setup>()
        }

    @Test
    fun endingBeforeAnyShotScoredSavesNothing() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.Start)
            viewModel.onEvent(GamesEvent.End)

            assertThat(activities.state.value).isEqualTo(emptyList())
            assertThat((viewModel.uiState.value as GamesUiState.Results).saved).isFalse()
        }

    @Test
    fun aDroppedLinkKeepsTheGameAndShotsBeforeStartAreIgnored() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            swing(shot(1, 80.0))
            viewModel.onEvent(GamesEvent.Start)
            shots.connectionState.value = ConnectionState.Connecting

            val playing = viewModel.playing()
            assertThat(playing.connected).isFalse()
            assertThat(playing.game.lastShot).isNull()
        }

    @Test
    fun setupEditsAreClampedAndOnlyApplyBeforeTheGame() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            viewModel.onEvent(GamesEvent.SetTargetYards(9_000.0))
            viewModel.onEvent(GamesEvent.SetPongCups(12))
            viewModel.onEvent(GamesEvent.RemovePlayer("p1"))
            repeat(5) { viewModel.onEvent(GamesEvent.AddPlayer) }

            val setup = (viewModel.uiState.value as GamesUiState.Setup).setup
            assertThat(setup.targetYards).isEqualTo(GameSetup.MAX_TARGET_YARDS)
            assertThat(setup.pongCups).isEqualTo(10)
            assertThat(setup.players.map { it.id }).containsExactly("p1", "p2", "p3", "p4")
            assertThat(setup.players.map { it.colorIndex }).containsExactly(0, 1, 2, 3)

            viewModel.onEvent(GamesEvent.Start)
            viewModel.onEvent(GamesEvent.SelectType(GameType.BULLSEYE))
            assertThat(viewModel.playing().game.type).isEqualTo(GameType.TARGET_CALLOUT)
        }

    @Test
    fun aMockPiOffersSimulateShotOverWifi() =
        runTest {
            shots.connectionState.value = ConnectionState.Connected
            val viewModel = observe(viewModel())
            assertThat(viewModel.uiState.value.simulate).isNull()

            piSession.mockMode.value = true
            piSession.linkState.value = PiLinkState.Connected
            assertThat(
                viewModel.uiState.value.simulate
                    ?.availability,
            ).isEqualTo(PiFeatureAvailability.Available)

            viewModel.onEvent(GamesEvent.SimulateShot)
            assertThat(piSession.commands).containsExactly("simulate_shot")
        }
}
