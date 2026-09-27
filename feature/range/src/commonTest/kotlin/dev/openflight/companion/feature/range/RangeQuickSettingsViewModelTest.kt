// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ShotEvent
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
 * Plan F8f: the range quick settings. Every control persists through the SettingsRepository key
 * Settings › Practice uses and comes back into the state from it; "Show" maps onto the overlay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeQuickSettingsViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository(settings)
    private val history = FakeShotHistoryRepository()

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
            piSession = FakePiSessionRepository(),
            flightPlan = ::cheapFlightPlan,
            computeDispatcher = StandardTestDispatcher(scheduler),
            distanceEstimate = { _, _, _ -> null },
        )

    /**
     * The current session "now" with [count] shots (ids 1..[count], newest first, every third a
     * 7-iron), and an older session "old" with three.
     */
    private fun seedSessions(count: Int = 30) {
        history.put(
            "now",
            (count.toLong() downTo 1L).map { id ->
                storedShot(
                    id,
                    sessionId = "now",
                    timestamp = "2026-09-27T10:" + id.toString().padStart(5, '0'),
                    club = if (id % 3 == 0L) "7-iron" else "driver",
                )
            },
        )
        history.put(
            "old",
            (103L downTo 101L).map { id ->
                storedShot(id, sessionId = "old", timestamp = "2026-09-20T10:" + id.toString().padStart(5, '0'))
            },
        )
        history.currentSessionId.value = "now"
    }

    @Test
    fun eachTrailViewAndNumbersControlPersistsAndUpdatesTheState() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetTrailStyle(ShotTrailStyle.COMET))
                viewModel.onEvent(DrivingRangeEvent.SetTrailKeepLast(10))
                viewModel.onEvent(DrivingRangeEvent.SetLandingEffect(LandingEffect.RING))
                viewModel.onEvent(DrivingRangeEvent.SetTheme(RangeThemeSetting.NIGHT))
                viewModel.onEvent(DrivingRangeEvent.SetCameraMode(RangeCameraMode.FIXED))
                viewModel.onEvent(DrivingRangeEvent.SetUnits(UnitSystem.METRIC))
                viewModel.onEvent(DrivingRangeEvent.SetShowTotal(false))

                val camera =
                    awaitUntil {
                        it.camera.trail.style == ShotTrailStyle.COMET &&
                            it.camera.trail.keepLast == 10 &&
                            it.camera.trail.landingEffect == LandingEffect.RING &&
                            it.camera.theme == RangeTheme.NIGHT &&
                            it.cameraMode == RangeCameraMode.FIXED &&
                            it.camera.numbers == RangeNumbers(UnitSystem.METRIC, showTotal = false)
                    }.camera
                assertThat(camera.theme.setting).isEqualTo(RangeThemeSetting.NIGHT)
                // The same keys Settings › Practice reads.
                assertThat(settings.shotTrail.value).isEqualTo(ShotTrailStyle.COMET)
                assertThat(settings.shotTrailKeepLast.value).isEqualTo(10)
                assertThat(settings.landingEffect.value).isEqualTo(LandingEffect.RING)
                assertThat(settings.rangeTheme.value).isEqualTo(RangeThemeSetting.NIGHT)
                assertThat(settings.rangeCameraMode.value).isEqualTo(RangeCameraMode.FIXED)
                assertThat(settings.units.value).isEqualTo(UnitSystem.METRIC)
                assertThat(settings.showTotalDistance.value).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aChangeMadeInSettingsReachesTheRangeToo() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                settings.setShotTrail(ShotTrailStyle.NEON)
                settings.setUnits(UnitSystem.METRIC)
                awaitUntil {
                    it.camera.trail.style == ShotTrailStyle.NEON &&
                        it.camera.numbers.units == UnitSystem.METRIC
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theCameraCantBeChangedWhileReducedMotionFixesIt() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.ReduceMotionChanged(enabled = true))
                viewModel.onEvent(DrivingRangeEvent.SetCameraMode(RangeCameraMode.FIXED))
                awaitUntil { it.cameraModeLocked }
                scheduler.advanceUntilIdle()
                assertThat(settings.rangeCameraMode.value).isEqualTo(RangeCameraMode.FOLLOW)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun keepLastTenKeepsTheTenNewestEarlierLiveFlights() =
        runTest(scheduler) {
            settings.shotTrailKeepLast.value = 10
            val viewModel = makeViewModel()
            val flown = List(12) { makeDrivingRangeShot() }

            viewModel.uiState.test {
                for (shot in flown) fly(viewModel, shot)
                val priors = expectMostRecentItem().camera.trail.priorFlights
                assertThat(priors.map { it.trajectory.eventId })
                    .isEqualTo(
                        flown
                            .dropLast(1)
                            .takeLast(10)
                            .reversed()
                            .map { it.eventId },
                    )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lastNOverlaysTheCurrentSessionsNewestNNewestFirstAndPersists() =
        runTest(scheduler) {
            seedSessions()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LAST_5))
                val five = awaitUntil { it.browse.overlayFlights.size == 5 && !it.browse.loading }
                assertThat(five.browse.overlayFlights.map { it.shotId }).containsExactly("30", "29", "28", "27", "26")
                assertThat(five.mode).isEqualTo(RangeMode.Overlay("now", null, 5))
                assertThat(five.browse.show).isEqualTo(RangeShowSetting.LAST_5)
                assertThat(five.browse.overlayTruncated).isFalse()
                assertThat(settings.rangeShow.value).isEqualTo(RangeShowSetting.LAST_5)

                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LAST_20))
                val twenty = awaitUntil { it.browse.overlayFlights.size == 20 && !it.browse.loading }
                assertThat(
                    twenty.browse.overlayFlights
                        .first()
                        .shotId,
                ).isEqualTo("30")
                assertThat(
                    twenty.browse.overlayFlights
                        .last()
                        .shotId,
                ).isEqualTo("11")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun thisSessionOverlaysAllOfTheCurrentSessionAndAllSessionsEverySession() =
        runTest(scheduler) {
            seedSessions()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.THIS_SESSION))
                val session = awaitUntil { it.browse.overlayFlights.size == 30 && !it.browse.loading }
                assertThat(session.mode).isEqualTo(RangeMode.Overlay("now", null, null))

                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.ALL_SESSIONS))
                val all = awaitUntil { it.browse.overlayFlights.size == 33 && !it.browse.loading }
                assertThat(all.mode).isEqualTo(RangeMode.Overlay(null, null, null))
                assertThat(all.browse.show).isEqualTo(RangeShowSetting.ALL_SESSIONS)
                assertThat(settings.rangeShow.value).isEqualTo(RangeShowSetting.ALL_SESSIONS)

                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LIVE))
                val live = awaitUntil { it.browse.isLive }
                assertThat(live.browse.overlayFlights).hasSize(0)
                assertThat(live.browse.show).isEqualTo(RangeShowSetting.LIVE)
                assertThat(settings.rangeShow.value).isEqualTo(RangeShowSetting.LIVE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theClubFilterAppliesBeforeTheLimitAndCarriesOverToTheNextShowChoice() =
        runTest(scheduler) {
            seedSessions()
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LAST_5))
                awaitUntil { it.browse.overlayFlights.size == 5 && !it.browse.loading }
                viewModel.onEvent(DrivingRangeEvent.SetOverlayClub("7-iron"))
                val irons = awaitUntil { it.mode == RangeMode.Overlay("now", "7-iron", 5) && !it.browse.loading }
                // The five newest 7-irons, not the 7-irons among the five newest shots.
                assertThat(irons.browse.overlayFlights.map { it.shotId }).containsExactly("30", "27", "24", "21", "18")
                assertThat(irons.browse.overlayClubs).containsExactly("driver", "7-iron")
                assertThat(irons.browse.show).isEqualTo(RangeShowSetting.LAST_5)

                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.THIS_SESSION))
                val session = awaitUntil { it.mode == RangeMode.Overlay("now", "7-iron", null) && !it.browse.loading }
                assertThat(session.browse.overlayFlights).hasSize(10)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun theStoredShowChoiceIsRestoredWhenTheRangeOpens() =
        runTest(scheduler) {
            seedSessions()
            settings.rangeShow.value = RangeShowSetting.LAST_10
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                val restored = awaitUntil { it.browse.overlayFlights.size == 10 && !it.browse.loading }
                assertThat(restored.mode).isEqualTo(RangeMode.Overlay("now", null, 10))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aViewOnRangeLaunchWinsOverTheStoredShowChoice() =
        runTest(scheduler) {
            seedSessions()
            settings.rangeShow.value = RangeShowSetting.ALL_SESSIONS
            val viewModel = makeViewModel()
            viewModel.onEvent(DrivingRangeEvent.Launch(RangeLaunch("old", shotId = null)))

            viewModel.uiState.test {
                val replay = awaitUntil { it.mode is RangeMode.Replay && !it.browse.loading }
                assertThat(replay.mode).isInstanceOf(RangeMode.Replay::class)
                assertThat(replay.browse.show).isNull()
                scheduler.advanceUntilIdle()
                assertThat(expectMostRecentItemOr(replay).mode).isInstanceOf(RangeMode.Replay::class)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLiveShotFliesOverTheCurrentSessionsOverlayAndJoinsIt() =
        runTest(scheduler) {
            seedSessions(count = 6)
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.LAST_5))
                awaitUntil { it.browse.overlayFlights.size == 5 && !it.browse.loading }

                val live = makeDrivingRangeShot()
                shots.emit(live)
                val flying = awaitUntil { it.phase == RangePhase.Flying && it.displayedShot?.eventId == live.eventId }
                assertThat(flying.activeFlight).isNotNull()
                assertThat(flying.browse.newLiveShot).isFalse()
                assertThat(flying.mode).isInstanceOf(RangeMode.Overlay::class)

                // The repository files it under the current session; the overlay picks it up.
                history.put(
                    "now",
                    listOf(storedShot(7, sessionId = "now", timestamp = "2026-09-27T10:00007")) +
                        (6L downTo 1L).map {
                            storedShot(
                                it,
                                sessionId = "now",
                                timestamp =
                                    "2026-09-27T10:" + it.toString().padStart(5, '0'),
                            )
                        },
                )
                val joined =
                    awaitUntil {
                        it.browse.overlayFlights
                            .firstOrNull()
                            ?.shotId == "7"
                    }
                assertThat(joined.browse.overlayFlights.map { it.shotId }).containsExactly("7", "6", "5", "4", "3")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLiveShotDuringAnAllSessionsOverlayStillOffersTheWayBack() =
        runTest(scheduler) {
            seedSessions(count = 3)
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                awaitItem()
                viewModel.onEvent(DrivingRangeEvent.SetShow(RangeShowSetting.ALL_SESSIONS))
                awaitUntil { it.browse.overlayFlights.size == 6 && !it.browse.loading }
                shots.emit(makeDrivingRangeShot())
                val chip = awaitUntil { it.browse.newLiveShot }
                assertThat(chip.activeFlight).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun showTotalOffHidesTheEstimatedRollOutAndUnitsConvertItsLabel() =
        runTest(scheduler) {
            val viewModel =
                DrivingRangeViewModel(
                    shots = shots,
                    settings = settings,
                    history = history,
                    conditions = FakeConditionsRepository(),
                    piSession = FakePiSessionRepository(),
                    flightPlan = { m, c, b -> testFlightPlan(m, c, b) },
                    computeDispatcher = StandardTestDispatcher(scheduler),
                )

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                val shown = awaitUntil { it.rollOut != null }.rollOut!!
                assertThat(shown.units).isEqualTo(UnitSystem.IMPERIAL)

                viewModel.onEvent(DrivingRangeEvent.SetUnits(UnitSystem.METRIC))
                val metric = awaitUntil { it.rollOut?.units == UnitSystem.METRIC }.rollOut!!
                assertThat(metric.totalYards).isEqualTo(shown.totalYards)
                assertThat(metric.totalLabel).isEqualTo("est. ${(shown.totalYards * 0.9144).toInt()}")

                viewModel.onEvent(DrivingRangeEvent.SetShowTotal(false))
                awaitUntil { it.rollOut == null && !it.camera.numbers.showTotal }
                cancelAndIgnoreRemainingEvents()
            }
        }

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
        fallback: DrivingRangeUiState,
    ): DrivingRangeUiState =
        try {
            expectMostRecentItem()
        } catch (_: AssertionError) {
            fallback
        }
}
