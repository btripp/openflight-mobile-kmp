// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import dev.openflight.companion.core.data.Activity
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.testing.FakeActivityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitiesViewModelTest {
    private val activities = FakeActivityRepository()
    private val ann = Player("ann", "Ann", 0)
    private val bob = Player("bob", "Bob", 1)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun finishedGame(
        mode: GameMode,
        carries: List<Double>,
    ): GameState {
        val events =
            listOf<GameEvent>(GameEvent.Start(10)) +
                carries.mapIndexed {
                    index,
                    carry,
                    ->
                    GameEvent.ShotFinal(GameShot("s$index", "pw", carry, offlineYards = 0.0))
                } +
                GameEvent.End(20)
        return GameSessionReducer.replay(GameConfig(mode, listOf(ann, bob)), events)
    }

    private fun activity(
        id: String,
        startedAt: Long,
        state: GameState,
    ): Activity =
        GameRecords.activity(id, state, UnitSystem.IMPERIAL, sessionId = "s-1").copy(startedAtEpochMillis = startedAt)

    @Test
    fun cardsAreNewestFirstAndFilterByType() =
        runTest {
            val callout = finishedGame(GameMode.TargetCallout(listOf(80.0)), listOf(83.0, 78.0))
            val bullseye = finishedGame(GameMode.Bullseye(100.0, shotsPerPlayer = 1), listOf(100.0, 125.0))
            activities.record(activity("a", 1, callout))
            activities.record(activity("b", 2, bullseye))
            val viewModel = ActivitiesViewModel(activities)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val all = viewModel.uiState.value as ActivitiesUiState.Content
            assertThat(all.cards.map { it.id }).containsExactly("b", "a")
            assertThat(all.cards.map { it.headline }).containsExactly("Ann won!", "Bob won!")
            assertThat(all.cards.first().typeTitle).isEqualTo("Bullseye")
            assertThat(all.filters.size).isEqualTo(GameType.entries.size + 1)

            viewModel.onEvent(ActivitiesEvent.SelectFilter(GameType.TARGET_CALLOUT))
            assertThat((viewModel.uiState.value as ActivitiesUiState.Content).cards.map { it.id }).containsExactly("a")

            viewModel.onEvent(ActivitiesEvent.SelectFilter(GameType.GOLF_PONG))
            assertThat(viewModel.uiState.value).isEqualTo(ActivitiesUiState.Empty(GameType.GOLF_PONG))
        }

    @Test
    fun theDetailReadsThePlayersResultAndShotsBack() =
        runTest {
            val tie = finishedGame(GameMode.TargetCallout(listOf(80.0)), listOf(83.0, 77.0))
            activities.record(activity("a", 1, tie))
            val viewModel = ActivitiesViewModel(activities)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.onEvent(ActivitiesEvent.Select("a"))

            val detail = (viewModel.uiState.value as ActivitiesUiState.Content).detail!!
            assertThat(detail.card.headline).isEqualTo("Tie: Ann & Bob")
            assertThat(detail.sessionId).isEqualTo("s-1")
            assertThat(detail.standings.map { Triple(it.name, it.totalLabel, it.isWinner) })
                .containsExactly(Triple("Ann", "3.0 yds", true), Triple("Bob", "3.0 yds", true))
            assertThat(detail.shots.map { it.label }).containsExactly("3.0 yds long", "3.0 yds short")

            viewModel.onEvent(ActivitiesEvent.Delete("a"))
            assertThat(viewModel.uiState.value).isInstanceOf<ActivitiesUiState.Empty>()
        }

    @Test
    fun anUnknownOrMalformedActivityStillShowsACard() =
        runTest {
            activities.record(Activity("x", "FUTURE_GAME", 1, null, "Something new", "7", "not json", "{", null))
            val viewModel = ActivitiesViewModel(activities)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            viewModel.onEvent(ActivitiesEvent.Select("x"))

            val content = viewModel.uiState.value as ActivitiesUiState.Content
            assertThat(content.cards.single().type).isNull()
            assertThat(content.cards.single().typeTitle).isEqualTo("FUTURE_GAME")
            assertThat(content.detail?.standings).isEqualTo(emptyList())
        }
}
