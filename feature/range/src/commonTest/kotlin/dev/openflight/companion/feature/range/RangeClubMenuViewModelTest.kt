// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ClubMenu
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.testing.FakeBagRepository
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Issue #15: the range's club picker follows the active bag. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeClubMenuViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository(club = GolfClub.DRIVER)
    private val shots = FakeShotRepository(settings)
    private val bags = FakeBagRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            history = FakeShotHistoryRepository(),
            conditions = FakeConditionsRepository(),
            piSession = FakePiSessionRepository(),
            flightPlan = { m, c, b -> testFlightPlan(m, c, b, ::makeTestTrajectory) },
            computeDispatcher = StandardTestDispatcher(scheduler),
            distanceEstimate = { _, _, _ -> null },
            bags = bags,
        )

    @Test
    fun withoutABagThePickerListsAllTwentyClubs() =
        runTest(scheduler) {
            makeViewModel().uiState.test {
                assertThat(awaitUntil { true }.club.menu).isEqualTo(ClubMenu.ALL)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun thePickerFollowsBagEditsLive() =
        runTest(scheduler) {
            val bagId =
                bags.createBag(
                    "Mine",
                    listOf(GolfClub.IRON_7, GolfClub.DRIVER, GolfClub.IRON_4),
                    makeActive = true,
                )

            makeViewModel().uiState.test {
                val menu =
                    awaitUntil { GolfClub.IRON_4 in it.club.menu.yourClubs && it.club.menu.hasOtherClubs }
                        .club.menu
                assertThat(menu.yourClubs).containsExactly(GolfClub.IRON_7, GolfClub.DRIVER, GolfClub.IRON_4)

                val fourIron =
                    bags.state.value
                        .single()
                        .clubs
                        .first { it.club == GolfClub.IRON_4 }
                bags.removeClub(fourIron.id)
                val removed = awaitUntil { GolfClub.IRON_4 !in it.club.menu.yourClubs }.club.menu
                assertThat(removed.yourClubs).containsExactly(GolfClub.IRON_7, GolfClub.DRIVER)
                assertThat(GolfClub.IRON_4 in removed.otherClubs).isTrue()

                bags.upsertClub(bagId, GolfClub.LOB_WEDGE)
                val added = awaitUntil { GolfClub.LOB_WEDGE in it.club.menu.yourClubs }.club.menu
                assertThat(added.yourClubs).containsExactly(GolfClub.IRON_7, GolfClub.DRIVER, GolfClub.LOB_WEDGE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aClubFromAllClubsIsSetThroughTheShotRepositoryAndStaysListed() =
        runTest(scheduler) {
            bags.createBag("Mine", listOf(GolfClub.DRIVER, GolfClub.IRON_7), makeActive = true)
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitUntil { it.club.menu.hasOtherClubs }

                viewModel.onEvent(DrivingRangeEvent.ClubSelected(GolfClub.IRON_4))
                val after = awaitUntil { it.club.selected == GolfClub.IRON_4 }.club.menu

                assertThat(settings.selectedClub.value).isEqualTo(GolfClub.IRON_4)
                assertThat(after.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.IRON_4)
                assertThat(GolfClub.IRON_4 in after.otherClubs).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }
}
