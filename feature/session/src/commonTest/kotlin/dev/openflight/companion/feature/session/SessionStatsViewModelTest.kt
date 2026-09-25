// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Plan R8f stats polish (Expo `stats.tsx`): club tabs follow the server, stale note, tiles. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStatsViewModelTest {
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository()
    private val piSession = FakePiSessionRepository(PiLinkState.Connecting)
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SessionViewModel(shots, settings, piSession, now = { "2026-09-24T18:30:05.123Z" })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun connectAnn() {
        piSession.linkState.value = PiLinkState.Connected
        piSession.profiles.value =
            ProfilesState(
                profiles = listOf(Profile(id = "ann", name = "Ann"), Profile(id = "bo", name = "Bo")),
                activeProfileId = "ann",
                loaded = true,
            )
        piSession.setSession(listOf(detail(2, profileId = "ann"), detail(1, profileId = "ann")))
    }

    @Test
    fun theTabsFollowTheServersClubWhenItHasShots() =
        runTest {
            connectAnn()
            piSession.setSession(
                listOf(
                    detail(3, club = "7-iron", profileId = "ann"),
                    detail(2, profileId = "ann"),
                    detail(1, profileId = "ann"),
                ),
            )
            piSession.club.value = "7-iron"

            viewModel.uiState.testIgnoringRest {
                val state = awaitUntil { it.allCount == 3 && it.selectedClub == "7-iron" }
                assertThat(state.stats.shotCount).isEqualTo(1)

                // A club without shots this session: every club.
                piSession.club.value = "pitching-wedge"
                assertThat(awaitUntil { it.selectedClub == null }.stats.shotCount).isEqualTo(3)

                piSession.club.value = "driver"
                awaitUntil { it.selectedClub == "driver" }
            }
        }

    @Test
    fun aTappedTabHoldsUntilTheClubOrTheProfileChanges() =
        runTest {
            connectAnn()
            piSession.setSession(
                listOf(
                    detail(3, club = "7-iron", profileId = "ann"),
                    detail(2, profileId = "ann"),
                    detail(1, profileId = "bo"),
                ),
            )
            piSession.club.value = "driver"

            viewModel.uiState.testIgnoringRest {
                awaitUntil { it.selectedClub == "driver" }
                viewModel.onEvent(SessionEvent.SelectClub(null))
                awaitUntil { it.selectedClub == null }

                // A new shot with the same club doesn't pull the tab back.
                piSession.setSession(listOf(detail(4, profileId = "ann")) + piSession.sessionShots.value)
                assertThat(awaitUntil { it.allCount == 3 }.selectedClub).isNull()

                // A profile change follows the club again: Bo has a driver shot.
                piSession.profiles.value = piSession.profiles.value.copy(activeProfileId = "bo")
                val bo = awaitUntil { it.allCount == 1 }
                assertThat(bo.selectedClub).isEqualTo("driver")
                assertThat(bo.profileName).isEqualTo("Bo")
            }
        }

    @Test
    fun aTabWhoseShotsAreAllGoneFallsBackToAll() =
        runTest {
            shots.setHistory(listOf(shot(2, club = "7-iron"), shot(1)))

            viewModel.uiState.testIgnoringRest {
                viewModel.onEvent(SessionEvent.SelectClub("7-iron"))
                awaitUntil { it.selectedClub == "7-iron" }

                shots.setHistory(listOf(shot(1)))

                val state = awaitUntil { it.allCount == 1 }
                assertThat(state.selectedClub).isNull()
                assertThat(state.shots.map { it.id }).containsExactly(shotId(1))
            }
        }

    @Test
    fun withNothingConnectedTheScreenSaysItShowsTheLastSession() =
        runTest {
            shots.setHistory(listOf(shot(1)))
            shots.connectionState.value = ConnectionState.Connected

            viewModel.uiState.testIgnoringRest {
                assertThat(awaitUntil { it.hasShots }.staleNote).isNull()

                shots.connectionState.value = ConnectionState.Connecting

                assertThat(awaitUntil { it.staleNote != null }.staleNote)
                    .isEqualTo("Not connected — showing the last session received.")
            }
        }

    @Test
    fun statTilesAddMinimumAndStandardDeviation() {
        // Expo `sessionStats.test.ts`: 100/110/120 → sample std dev 10.
        val speeds =
            listOf(detail(3, ballSpeedMph = 120.0), detail(2, ballSpeedMph = 110.0), detail(1, ballSpeedMph = 100.0))
        val stats = computeDetailStats(speeds)

        val tiles = sessionStatTiles(stats, swingStats = null, units = UnitSystem.IMPERIAL)

        assertThat(tiles.map { it.label }).containsExactly(
            "Shots",
            "Avg Ball (mph)",
            "Max Ball (mph)",
            "Min Ball (mph)",
            "Ball Std Dev (mph)",
            "Avg Carry (yds)",
            "Avg Club (mph)",
            "Avg Smash",
        )
        assertThat(tiles.first { it.label == "Min Ball (mph)" }.value).isEqualTo("100.0")
        val spread = tiles.first { it.label == "Ball Std Dev (mph)" }
        assertThat(spread.value).isEqualTo("10.0")
        assertThat(spread.spoken).isEqualTo("Ball speed standard deviation, 10.0 mph")
        // No club speed reported: an em dash, spoken as "not available".
        assertThat(tiles.first { it.label == "Avg Club (mph)" }.spoken).isEqualTo("Average club speed, not available")
    }

    @Test
    fun oneShotHasNoStandardDeviation() {
        val stats = computeDetailStats(listOf(detail(1)))

        val tiles = sessionStatTiles(stats, swingStats = null, units = UnitSystem.METRIC)

        assertThat(tiles.first { it.label == "Ball Std Dev (km/h)" }.value).isEqualTo("—")
        assertThat(tiles.first { it.label == "Min Ball (km/h)" }.value).isEqualTo("225.3")
    }

    /** Like `test`, but tolerates the extra intermediate states `combine` may emit after the assertions. */
    private suspend fun <T> Flow<T>.testIgnoringRest(block: suspend ReceiveTurbine<T>.() -> Unit) =
        test {
            block()
            cancelAndIgnoreRemainingEvents()
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
