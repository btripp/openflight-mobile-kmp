// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Plan F8d device tests: "View on range" opens paused on the shot, and Simulate on a `--mock` Pi. */
@RunWith(AndroidJUnit4::class)
class RangeEverywhereTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val history = FakeShotHistoryRepository()
    private val shots = FakeShotRepository()
    private val piSession = FakePiSessionRepository()

    /** Phases the view model went through while showing [LIVE_EVENT_ID]. */
    private val livePhases = Collections.synchronizedList(mutableListOf<RangePhase>())

    private fun setRange(
        windowClass: OfWindowClass,
        replaySessionId: String? = null,
        replayShotId: String? = null,
        pi: PiSessionRepository = piSession,
    ): DrivingRangeViewModel {
        var created: DrivingRangeViewModel? = null
        composeRule.setContent {
            OfTheme {
                val viewModel =
                    remember {
                        DrivingRangeViewModel(shots, FakeSettingsRepository(), history, FakeConditionsRepository(), pi)
                    }
                created = viewModel
                LaunchedEffect(viewModel) {
                    viewModel.uiState.collect { state ->
                        if (state.displayedShot?.eventId == LIVE_EVENT_ID) livePhases += state.phase
                    }
                }
                DrivingRangeRoute(
                    onExit = {},
                    viewModel = viewModel,
                    reduceMotion = true,
                    replaySessionId = replaySessionId,
                    windowClass = windowClass,
                    replayShotId = replayShotId,
                )
            }
        }
        composeRule.waitForIdle()
        return assertNotNull(created)
    }

    @Test
    fun given_historyShot_when_launchedOnIt_then_rangeOpensPausedOnThatShot() {
        seedSession()
        val viewModel = setRange(OfWindowClass.COMPACT, replaySessionId = "s1", replayShotId = "2026-09-25T10:00:02")

        composeRule.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.mode == RangeMode.Replay("s1", 1) }
        composeRule.onNodeWithTag(RangeTestTags.POSITION).assert(hasText("2 / 3"))
        composeRule.onNodeWithTag(RangeTestTags.PLAY_PAUSE).assert(hasText("Play"))
        assertEquals(
            "7-iron",
            viewModel.uiState.value.displayedShot
                ?.club,
        )
    }

    @Test
    fun given_tablet_when_launchedOnAShot_then_sidePaneSelectsIt() {
        seedSession()
        setRange(OfWindowClass.EXPANDED, replaySessionId = "s1", replayShotId = "3")

        composeRule.onNodeWithTag(RangeTestTags.SHOT_LIST).assertIsDisplayed()
        composeRule.waitUntil(TIMEOUT_MILLIS) { isSelectedShot("3") }
        composeRule.onNodeWithTag(RangeTestTags.POSITION).assert(hasText("3 / 3"))
        composeRule.onNodeWithTag(RangeTestTags.PLAY_PAUSE).assert(hasText("Play"))
    }

    @Test
    fun given_mockWifi_when_simulate_then_liveShotFlies() {
        piSession.mockMode.value = true
        // The Pi answers simulate_shot with a new shot on the normal live path.
        val pi = SimulatingPi(piSession) { shots.emitLive() }
        setRange(OfWindowClass.COMPACT, pi = pi)

        composeRule.onNodeWithTag(RangeTestTags.SIMULATE).assertIsDisplayed().performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) { RangePhase.Flying in livePhases.toList() }
        assertEquals(listOf("simulate_shot"), piSession.commands)
    }

    @Test
    fun given_notMock_or_bluetooth_when_rangeOpens_then_noSimulateButton() {
        piSession.mockMode.value = false
        setRange(OfWindowClass.COMPACT)
        composeRule.onNodeWithTag(RangeTestTags.SIMULATE).assertDoesNotExist()

        composeRule.runOnIdle {
            piSession.mockMode.value = true
            piSession.linkState.value = PiLinkState.WifiOnly
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(RangeTestTags.SIMULATE).assertDoesNotExist()

        composeRule.runOnIdle { piSession.linkState.value = PiLinkState.Connected }
        composeRule.onNodeWithTag(RangeTestTags.SIMULATE).assertIsDisplayed()
    }

    private fun seedSession() {
        history.put("s1", listOf(stored(3, "pw", 120.0), stored(2, "7-iron", 170.0), stored(1, "driver", 250.0)))
    }

    private fun isSelectedShot(id: String): Boolean =
        composeRule
            .onAllNodes(hasTestTag(RangeTestTags.shot(id)) and isSelected())
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun stored(
        id: Long,
        club: String,
        carry: Double,
    ): HistoryShot =
        HistoryShot(
            id = id,
            sessionId = "s1",
            eventId = null,
            detail =
                ShotDetail(
                    timestamp = "2026-09-25T10:00:0$id",
                    club = club,
                    ballSpeedMph = if (club == "driver") 150.0 else 100.0,
                    estimatedCarryYards = carry,
                    launchAngleVertical = if (club == "driver") 12.0 else 24.0,
                    spinRpm = if (club == "driver") 2_500.0 else 7_500.0,
                ),
        )

    private fun FakeShotRepository.emitLive() {
        val live =
            ShotEvent(
                schemaVersion = 1,
                eventId = LIVE_EVENT_ID,
                timestamp = "2026-09-25T11:00:00",
                club = "driver",
                ballSpeedMph = 151.4,
                estimatedCarryYards = 264.0,
                launchAngleVertical = 12.6,
                spinRpm = 2_380.0,
            )
        history.value = listOf(live) + history.value
        latestShot.value = live
    }

    /** A `--mock` Pi: [simulateShot] records the command, then [onSimulate] plays the Pi's shot. */
    private class SimulatingPi(
        private val fake: FakePiSessionRepository,
        private val onSimulate: () -> Unit,
    ) : PiSessionRepository by fake {
        override suspend fun simulateShot() {
            fake.simulateShot()
            onSimulate()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val LIVE_EVENT_ID = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1"
    }
}
