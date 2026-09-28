// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ClubMenu
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.testing.FakeBagRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Issue #15: the dashboard's club menu follows the active bag. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardClubMenuTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, club = GolfClub.DRIVER)
    private val shots = FakeShotRepository(settings)
    private val bags = FakeBagRepository()

    private fun viewModel() =
        DashboardViewModel(shots, settings, FakePiSessionRepository(), ClubConfirmation(), bags = bags)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withoutABagTheMenuListsAllTwentyClubs() =
        runTest {
            viewModel().uiState.test {
                assertThat(awaitItem().connection.clubMenu).isEqualTo(ClubMenu.ALL)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theMenuListsTheActiveBagInBagOrderThenTheRest() =
        runTest {
            bags.createBag("Mine", listOf(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.PITCHING_WEDGE), makeActive = true)

            viewModel().uiState.test {
                val menu = awaitUntil { it.connection.clubMenu != ClubMenu.ALL }.connection.clubMenu

                assertThat(menu.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.PITCHING_WEDGE)
                assertThat(menu.otherClubs.size).isEqualTo(GolfClub.entries.size - 3)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theMenuFollowsBagEditsLive() =
        runTest {
            val bagId = bags.createBag("Mine", listOf(GolfClub.DRIVER, GolfClub.IRON_4), makeActive = true)

            viewModel().uiState.test {
                awaitUntil { GolfClub.IRON_4 in it.connection.clubMenu.yourClubs }

                val fourIron =
                    bags.state.value
                        .single()
                        .clubs
                        .first { it.club == GolfClub.IRON_4 }
                bags.removeClub(fourIron.id)
                val removed = awaitUntil { GolfClub.IRON_4 !in it.connection.clubMenu.yourClubs }.connection.clubMenu
                assertThat(removed.yourClubs).containsExactly(GolfClub.DRIVER)
                assertThat(GolfClub.IRON_4 in removed.otherClubs).isTrue()

                bags.upsertClub(bagId, GolfClub.SAND_WEDGE)
                val added = awaitUntil { GolfClub.SAND_WEDGE in it.connection.clubMenu.yourClubs }.connection.clubMenu
                assertThat(added.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.SAND_WEDGE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun switchingTheActiveBagSwitchesTheMenu() =
        runTest {
            bags.createBag("Long", listOf(GolfClub.DRIVER, GolfClub.WOOD_3), makeActive = true)
            val short = bags.createBag("Short", listOf(GolfClub.DRIVER, GolfClub.LOB_WEDGE))

            viewModel().uiState.test {
                awaitUntil { GolfClub.WOOD_3 in it.connection.clubMenu.yourClubs }

                bags.setActive(checkNotNull(short))
                val menu = awaitUntil { GolfClub.LOB_WEDGE in it.connection.clubMenu.yourClubs }.connection.clubMenu
                assertThat(menu.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.LOB_WEDGE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aClubFromAllClubsIsSetThroughTheShotRepositoryAndThenShowsWithTheBag() =
        runTest {
            bags.createBag("Mine", listOf(GolfClub.DRIVER, GolfClub.IRON_7), makeActive = true)
            val viewModel = viewModel()

            viewModel.uiState.test {
                val before = awaitUntil { it.connection.clubMenu != ClubMenu.ALL }.connection.clubMenu
                assertThat(GolfClub.IRON_4 in before.otherClubs).isTrue()

                viewModel.onEvent(DashboardEvent.ClubSelected(GolfClub.IRON_4))
                val after = awaitUntil { it.connection.club == GolfClub.IRON_4 }.connection.clubMenu

                assertThat(shots.setClubCalls).containsExactly(GolfClub.IRON_4)
                assertThat(after.yourClubs).containsExactly(GolfClub.DRIVER, GolfClub.IRON_7, GolfClub.IRON_4)
                assertThat(GolfClub.IRON_4 in after.otherClubs).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun ReceiveTurbine<DashboardUiState>.awaitUntil(
        predicate: (DashboardUiState) -> Boolean,
    ): DashboardUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
