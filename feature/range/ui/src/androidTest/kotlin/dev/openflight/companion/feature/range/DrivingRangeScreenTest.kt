// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.flight.FlightInputProvenance
import dev.openflight.companion.core.flight.FlightParameter
import dev.openflight.companion.core.flight.FlightPoint
import dev.openflight.companion.core.flight.FlightTrajectory
import dev.openflight.companion.core.flight.Vec3
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class DrivingRangeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<DrivingRangeEvent>()
    private var exits = 0

    private fun show(state: DrivingRangeUiState) {
        composeRule.setContent {
            OfTheme {
                DrivingRangeScreen(
                    uiState = state,
                    reduceMotion = true,
                    onEvent = { events += it },
                    onExit = { exits++ },
                )
            }
        }
    }

    @Test
    fun givenAnEstimatedFlight_whenFlying_thenTheOverlayShowsCarryAndTheEstimatedBadge() {
        show(DrivingRangeUiState.Showing(shot, RangePhase.Flying, ActiveFlight(estimatedTrajectory, playbackId = 1)))

        // OfMetricPrimary merges its title/value/unit into one TalkBack/VoiceOver stop
        // (accessibility task 3), so inspecting the individual leaf Text nodes below needs the
        // unmerged tree.
        composeRule
            .onAllNodes(hasText("264") and hasAnyAncestor(hasTestTag(RangeTestTags.CARRY)), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule
            .onAllNodes(
                hasText("151.4") and hasAnyAncestor(hasTestTag(RangeTestTags.BALL_SPEED)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule.onNodeWithTag(RangeTestTags.ESTIMATED).assertIsDisplayed()
        composeRule.onNodeWithText("Estimated flight uses club defaults").assertIsDisplayed()
        composeRule.onNodeWithText("Ball in flight").assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(RangeTestTags.REPLAY)).assertCountEquals(0)
    }

    @Test
    fun givenAFlyingShot_whenThePlaybackEnds_thenFlightCompletedIsSent() {
        show(DrivingRangeUiState.Showing(shot, RangePhase.Flying, ActiveFlight(estimatedTrajectory, playbackId = 1)))

        // Reduced motion: 0.9 s of frames on the test clock.
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.waitForIdle()

        assertEquals(listOf<DrivingRangeEvent>(DrivingRangeEvent.FlightCompleted), events)
    }

    @Test
    fun givenNoShot_whenShown_thenTheReadyCardShowsAndMetricsAreDashes() {
        show(DrivingRangeUiState.Ready())

        composeRule.onNodeWithTag(RangeTestTags.READY_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Driving Range Ready").assertIsDisplayed()
        composeRule
            .onAllNodes(hasText("—") and hasAnyAncestor(hasTestTag(RangeTestTags.CARRY)), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodes(hasTestTag(RangeTestTags.REPLAY)).assertCountEquals(0)
    }

    @Test
    fun givenALandedShot_whenReplayIsTapped_thenReplayIsSent() {
        show(DrivingRangeUiState.Showing(shot, RangePhase.Landed, activeFlight = null))

        composeRule.onAllNodes(hasTestTag(RangeTestTags.READY_CARD)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(RangeTestTags.ESTIMATED)).assertCountEquals(0)
        composeRule.onNodeWithTag(RangeTestTags.REPLAY).performClick()

        assertEquals(listOf<DrivingRangeEvent>(DrivingRangeEvent.Replay), events)
    }

    @Test
    fun givenAnUnavailableShot_whenShown_thenTheStatusShowsWhy() {
        show(
            DrivingRangeUiState.Showing(shot, RangePhase.Unavailable("Ball speed is unavailable for this shot."), null),
        )

        composeRule.onNodeWithText("Ball speed is unavailable for this shot.").assertIsDisplayed()
    }

    @Test
    fun givenAClubError_whenShown_thenItShowsInTheOverlay() {
        show(
            DrivingRangeUiState.Showing(
                shot,
                RangePhase.Waiting,
                activeFlight = null,
                club = RangeClubState(GolfClub.IRON_7, selectionEnabled = true, error = "busy"),
            ),
        )

        composeRule.onNodeWithTag(RangeTestTags.CLUB_ERROR).assertIsDisplayed()
        // The menu is clickable, so its text merges into the tagged node.
        composeRule
            .onNodeWithTag(RangeTestTags.CLUB_SELECTOR)
            .assert(hasText("7-Iron"))
            .assertIsEnabled()
    }

    @Test
    fun givenNotConnected_whenShown_thenTheClubSelectorIsDisabled() {
        show(DrivingRangeUiState.Ready(RangeClubState(selectionEnabled = false)))

        composeRule.onNodeWithTag(RangeTestTags.CLUB_SELECTOR).assertIsNotEnabled()
    }

    @Test
    fun givenTheRange_whenExitIsTapped_thenItExits() {
        show(DrivingRangeUiState.Ready())

        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()

        assertEquals(1, exits)
    }

    private companion object {
        val shot =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10",
                club = "driver",
                ballSpeedMph = 151.4,
                clubSpeedMph = 103.2,
                smashFactor = 1.47,
                estimatedCarryYards = 264.0,
                launchAngleVertical = 12.6,
                launchAngleHorizontal = -1.3,
                spinRpm = 2380.0,
                clubPathDeg = 2.1,
                spinAxisDeg = -3.4,
            )

        val estimatedTrajectory =
            FlightTrajectory(
                eventId = shot.eventId,
                points =
                    listOf(
                        FlightPoint(0.0, Vec3.ZERO, Vec3(0.0, 10.0, 40.0)),
                        FlightPoint(1.0, Vec3(0.0, 0.0, 241.4), Vec3(0.0, -10.0, 30.0)),
                    ),
                provenance = FlightInputProvenance(estimatedParameters = setOf(FlightParameter.SPIN_RATE)),
            )
    }
}
