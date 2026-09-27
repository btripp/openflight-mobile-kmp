// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.ViewingProfile
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
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

/**
 * Plan F8f: two phones on one Pi share its session, so each shows only its "Viewing profile":
 * the Pi's active profile by default, a pinned one, or everyone; with no roster, everyone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeProfileViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository(settings)
    private val history = FakeShotHistoryRepository()
    private val piSession = FakePiSessionRepository()

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
            history = history,
            conditions = FakeConditionsRepository(),
            piSession = piSession,
            flightPlan = ::cheapFlightPlan,
            computeDispatcher = StandardTestDispatcher(scheduler),
            distanceEstimate = { _, _, _ -> null },
        )

    private fun roster(active: String = ANN) {
        piSession.profiles.value =
            ProfilesState(listOf(Profile(ANN, "Ann"), Profile(BO, "Bo")), activeProfileId = active, loaded = true)
    }

    /** The current session "now": Ann's shots 1, 3, 5 and Bo's 2, 4 (newest first), plus one with no profile. */
    private fun seedSession() {
        history.put(
            "now",
            (6L downTo 1L).map {
                stored(
                    it,
                    if (it == 6L) {
                        null
                    } else if (it % 2 == 1L) {
                        ANN
                    } else {
                        BO
                    },
                )
            },
        )
        history.currentSessionId.value = "now"
    }

    @Test
    fun followActiveFliesOnlyTheActiveProfilesLiveShotsAndFollowsAChange() =
        runTest(scheduler) {
            roster(active = ANN)
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitUntil { it.browse.profiles.filterProfileId == ANN }
                val bos = live(BO)
                shots.emit(bos)
                scheduler.advanceUntilIdle()
                assertThat(expectMostRecentItemOr(null)?.displayedShot).isNull()

                val anns = live(ANN)
                shots.emit(anns)
                awaitUntil { it.displayedShot?.eventId == anns.eventId }

                // Bo becomes the Pi's active profile: Ann's shot leaves the screen for Bo's latest.
                roster(active = BO)
                val switched =
                    awaitUntil {
                        it.browse.profiles.filterProfileId == BO &&
                            it.displayedShot?.eventId == bos.eventId
                    }
                assertThat(switched.activeFlight).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            // Never switches the Pi's active profile: that would switch every other phone too.
            assertThat(piSession.commands.none { it.startsWith("set_active_profile") }).isTrue()
        }

    @Test
    fun aPinnedProfileIgnoresOtherProfilesLiveShotsWhateverIsActive() =
        runTest(scheduler) {
            roster(active = ANN)
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetViewingProfile(ViewingProfile.Pinned(BO)))
                awaitUntil { it.browse.profiles.filterProfileId == BO }
                assertThat(settings.viewingProfile.value).isEqualTo(ViewingProfile.Pinned(BO))

                shots.emit(live(ANN))
                scheduler.advanceUntilIdle()
                assertThat(expectMostRecentItemOr(null)?.displayedShot).isNull()
                val bos = live(BO)
                shots.emit(bos)
                awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == bos.eventId }
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(piSession.commands.none { it.startsWith("set_active_profile") }).isTrue()
        }

    @Test
    fun theOverlaysAndReplayShowOnlyTheViewingProfileAndAllProfilesShowsEveryone() =
        runTest(scheduler) {
            roster(active = ANN)
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitUntil { it.browse.profiles.filterProfileId == ANN }
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.THIS_SESSION))
                val anns = awaitUntil { it.browse.overlayFlights.size == 3 && !it.browse.loading }
                assertThat(anns.browse.overlayFlights.map { it.shotId }).containsExactly("5", "3", "1")

                viewModel.onEvent(DrivingRangeEvent.SetViewingProfile(ViewingProfile.AllProfiles))
                val everyone = awaitUntil { it.browse.overlayFlights.size == 6 && !it.browse.loading }
                assertThat(everyone.browse.profiles.filterProfileId).isNull()

                viewModel.onEvent(DrivingRangeEvent.SetViewingProfile(ViewingProfile.Pinned(BO)))
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LAST_5))
                val bos =
                    awaitUntil {
                        it.mode == RangeMode.Overlay("now", null, 5) &&
                            it.browse.overlayFlights.size == 2
                    }
                assertThat(bos.browse.overlayFlights.map { it.shotId }).containsExactly("4", "2")

                viewModel.onEvent(DrivingRangeEvent.StartReplay("now"))
                val replay =
                    awaitUntil {
                        it.mode is RangeMode.Replay && !it.browse.loading &&
                            it.browse.shots.isNotEmpty()
                    }
                assertThat(replay.browse.shots.map { it.id }).containsExactly("2", "4")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun keptTrailsAreClearedWhenTheViewingProfileChanges() =
        runTest(scheduler) {
            roster(active = ANN)
            settings.shotTrailKeepLast.value = 3
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitUntil { it.browse.profiles.filterProfileId == ANN }
                fly(viewModel, live(ANN))
                fly(viewModel, live(ANN))
                assertThat(
                    expectMostRecentItem()
                        .camera.trail.priorFlights.size,
                ).isEqualTo(1)

                viewModel.onEvent(DrivingRangeEvent.SetViewingProfile(ViewingProfile.Pinned(BO)))
                // The trail and the browse state reach the UI state separately; wait for both.
                awaitUntil {
                    it.browse.profiles.filterProfileId == BO &&
                        it.camera.trail.priorFlights
                            .isEmpty()
                }
                scheduler.advanceUntilIdle()
                assertThat(
                    expectMostRecentItemOr(null)
                        ?.camera
                        ?.trail
                        ?.priorFlights
                        .orEmpty(),
                ).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun withoutARosterEveryShotShowsAndTheControlHides() =
        runTest(scheduler) {
            seedSession()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                val first = awaitItem()
                assertThat(first.browse.profiles.available).isFalse()
                val anyone = live(profileId = null)
                shots.emit(anyone)
                awaitUntil { it.displayedShot?.eventId == anyone.eventId }
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.THIS_SESSION))
                awaitUntil { it.browse.overlayFlights.size == 6 && !it.browse.loading }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aPinnedProfileThatLeftTheRosterFallsBackToTheActiveOne() {
        val state =
            RangeProfileState.of(
                ViewingProfile.Pinned("gone"),
                ProfilesState(listOf(Profile(ANN, "Ann")), activeProfileId = ANN, loaded = true),
            )
        assertThat(state.filterProfileId).isEqualTo(ANN)
        assertThat(state.shows(ANN)).isTrue()
        assertThat(state.shows(null)).isFalse()
        assertThat(state.shows("")).isFalse()
        // A roster that hasn't loaded counts as none.
        assertThat(RangeProfileState.of(ViewingProfile.FollowActive, ProfilesState(loaded = false)).shows(BO)).isTrue()
    }

    private var nextLive = 0

    private fun live(profileId: String?): ShotEvent =
        makeDrivingRangeShot(eventId = "B0D91F0A-7950-4D7E-9DD5-" + (900 + nextLive++).toString().padStart(12, '0'))
            .copy(profileId = profileId)

    private fun stored(
        id: Long,
        profileId: String?,
    ): HistoryShot =
        storedShot(
            id,
            sessionId = "now",
            timestamp = "2026-09-27T10:" + id.toString().padStart(5, '0'),
            detail =
                ShotDetail(
                    timestamp = "2026-09-27T10:" + id.toString().padStart(5, '0'),
                    club = "driver",
                    ballSpeedMph = 150.0,
                    estimatedCarryYards = 200.0,
                    launchAngleVertical = 12.0,
                    spinRpm = 2_500.0,
                    profileId = profileId,
                ),
        )

    /** Flies [shot] live to its landing and past the dwell. */
    private suspend fun ReceiveTurbine<DrivingRangeUiState>.fly(
        viewModel: DrivingRangeViewModel,
        shot: ShotEvent,
    ) {
        shots.emit(shot)
        awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == shot.eventId }
        viewModel.onEvent(DrivingRangeEvent.FlightCompleted)
        scheduler.advanceTimeBy(DrivingRangeViewModel.LANDING_DWELL_MILLIS + 1)
        scheduler.runCurrent()
    }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private fun ReceiveTurbine<DrivingRangeUiState>.expectMostRecentItemOr(
        fallback: DrivingRangeUiState?,
    ): DrivingRangeUiState? =
        try {
            expectMostRecentItem()
        } catch (_: AssertionError) {
            fallback
        }

    private companion object {
        const val ANN = "ann"
        const val BO = "bo"
    }
}
