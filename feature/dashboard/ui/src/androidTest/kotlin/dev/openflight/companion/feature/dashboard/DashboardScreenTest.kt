// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<DashboardEvent>()

    private fun show(state: DashboardUiState) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(uiState = state, onEvent = { events += it }, onOpenCalibration = {}, onOpenRange = {})
            }
        }
    }

    @Test
    fun givenAShotWithoutOptionalMetrics_whenShown_thenTheyRenderAsDashesWithoutUnits() {
        show(DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), bareShot, emptyList()))

        // OfMetricDetail merges its title/value/unit into one TalkBack/VoiceOver stop
        // (accessibility task 3), so inspecting the individual leaf Text nodes below needs the
        // unmerged tree.
        for (title in listOf("Club speed", "Smash", "Launch", "Direction", "Spin", "Club path", "Spin axis")) {
            val cell = hasAnyAncestor(hasTestTag(DashboardTestTags.metric(title)))
            composeRule.onAllNodes(hasText("—") and cell, useUnmergedTree = true).assertCountEquals(1)
        }
        for (unit in listOf("mph", "°", "rpm")) {
            composeRule
                .onAllNodes(
                    hasText(unit, substring = true) and hasAnyAncestor(hasTestTag(DashboardTestTags.LATEST_SHOT)),
                    useUnmergedTree = true,
                ).assertCountEquals(0)
        }
        composeRule.onNodeWithText("151.4").assertIsDisplayed()
        composeRule.onNodeWithText("264").assertIsDisplayed()
    }

    @Test
    fun givenAFullShot_whenShown_thenMetricsRenderWithUnits() {
        show(DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), fullShot, emptyList()))

        val spin = hasAnyAncestor(hasTestTag(DashboardTestTags.metric("Spin")))
        composeRule.onAllNodes(hasText("2,380") and spin, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("rpm", substring = true) and spin, useUnmergedTree = true).assertCountEquals(1)
        composeRule
            .onAllNodes(
                hasText("1.47") and hasAnyAncestor(hasTestTag(DashboardTestTags.metric("Smash"))),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }

    @Test
    fun givenNotConnected_whenStatesChange_thenTheClubMenuIsEnabledOnlyWhenConnected() {
        var state: ConnectionState by mutableStateOf(ConnectionState.Idle)
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState = DashboardUiState.Waiting(ConnectionPanelState(state = state)),
                    onEvent = {},
                    onOpenCalibration = {},
                    onOpenRange = {},
                )
            }
        }

        for (notConnected in listOf(ConnectionState.Idle, ConnectionState.Scanning, ConnectionState.Error("Lost"))) {
            state = notConnected
            composeRule.onNodeWithTag(DashboardTestTags.CLUB_SELECTOR).assertIsNotEnabled()
        }
        state = ConnectionState.Connected
        composeRule.onNodeWithTag(DashboardTestTags.CLUB_SELECTOR).assertIsEnabled()
    }

    @Test
    fun givenConnectedWhileAClubChangeIsInFlight_whenShown_thenTheClubMenuIsDisabled() {
        show(DashboardUiState.Waiting(ConnectionPanelState(state = ConnectionState.Connected, isChangingClub = true)))

        composeRule.onNodeWithTag(DashboardTestTags.CLUB_SELECTOR).assertIsNotEnabled()
    }

    @Test
    fun givenScanning_whenShown_thenRetryIsHiddenAndProgressShows() {
        show(DashboardUiState.Waiting(ConnectionPanelState(state = ConnectionState.Scanning)))

        composeRule.onAllNodes(hasTestTag(DashboardTestTags.RETRY)).assertCountEquals(0)
        composeRule.onNodeWithTag(DashboardTestTags.PROGRESS).assertIsDisplayed()
        composeRule.onNodeWithText("Looking for OpenFlight").assertIsDisplayed()
    }

    @Test
    fun givenConnected_whenShown_thenNeitherRetryNorProgressShows() {
        show(DashboardUiState.Waiting(ConnectionPanelState(state = ConnectionState.Connected)))

        composeRule.onAllNodes(hasTestTag(DashboardTestTags.RETRY)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(DashboardTestTags.PROGRESS)).assertCountEquals(0)
        composeRule.onNodeWithText("OpenFlight Pi").assertIsDisplayed()
    }

    @Test
    fun givenAnError_whenRetryIsTapped_thenRetryIsSent() {
        show(DashboardUiState.Waiting(ConnectionPanelState(state = ConnectionState.Error("Too many devices"))))

        composeRule.onNodeWithText("Too many devices").assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.RETRY).performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.Retry), events)
    }

    @Test
    fun givenWifi_whenShown_thenTheHostFieldShows() {
        show(DashboardUiState.Waiting(ConnectionPanelState(transport = TransportType.WIFI, hostText = "10.0.2.2:8091")))

        composeRule.onNodeWithTag(DashboardTestTags.HOST_FIELD).assertIsDisplayed()
        composeRule.onNodeWithText("10.0.2.2:8091").assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.EMPTY_STATE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun givenBluetooth_whenShown_thenTheHostFieldIsHidden() {
        show(DashboardUiState.Waiting(ConnectionPanelState(transport = TransportType.BLUETOOTH)))

        composeRule.onAllNodes(hasTestTag(DashboardTestTags.HOST_FIELD)).assertCountEquals(0)
    }

    @Test
    fun givenAClubError_whenTapped_thenItIsDismissed() {
        show(DashboardUiState.Waiting(ConnectionPanelState(state = ConnectionState.Connected, clubError = "busy")))

        composeRule.onNodeWithTag(DashboardTestTags.CLUB_ERROR).performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.DismissError), events)
    }

    @Test
    fun givenADegreeMetric_whenShown_thenItsContentDescriptionIsOneMergedPhrase() {
        show(DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), fullShot, emptyList()))

        // A screen reader must announce the title, value and unit as one phrase, and spell out
        // the degree sign, instead of stopping on "Launch", then "12.6", then "°" separately.
        composeRule
            .onNodeWithTag(DashboardTestTags.metric("Launch"))
            .assert(hasContentDescriptionExactly("Launch, 12.6 degrees"))
    }

    @Test
    fun givenPreviousShots_whenShown_thenTheHistoryCardListsThem() {
        val previous = listOf(fullShot.copy(eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E2", club = "7-iron"))
        show(DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), fullShot, previous))

        composeRule.onNodeWithTag(DashboardTestTags.PREVIOUS_SHOTS).performScrollTo().assertIsDisplayed()
        composeRule
            .onAllNodes(hasText("7-Iron") and hasAnyAncestor(hasTestTag(DashboardTestTags.PREVIOUS_SHOTS)))
            .assertCountEquals(1)
    }

    private companion object {
        val fullShot =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10.123456",
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
        val bareShot =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E3",
                timestamp = "2026-07-29T19:42:10",
                club = "driver",
                ballSpeedMph = 151.4,
                estimatedCarryYards = 264.0,
            )
    }
}
