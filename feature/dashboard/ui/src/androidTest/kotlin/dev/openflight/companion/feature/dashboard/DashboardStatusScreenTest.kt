// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotProcessingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plan R8f on the Android dashboard: the processing indicator and connection problems. */
@RunWith(AndroidJUnit4::class)
class DashboardStatusScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: DashboardUiState) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(uiState = state, onEvent = {}, onOpenCalibration = {}, onOpenRange = {})
            }
        }
    }

    private val connected = ConnectionPanelState(transport = TransportType.WIFI, state = ConnectionState.Connected)

    @Test
    fun givenCapturing_whenShown_thenTheIndicatorSaysSoAndIsALiveRegion() {
        show(
            DashboardUiState.Waiting(
                connected,
                processing = ProcessingIndicator.of(ShotProcessingState.CAPTURING),
            ),
        )

        composeRule.onNodeWithText("Impact detected", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Capturing radar data…", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf("Impact detected. Capturing radar data…"),
                    ),
            ).assertExists()
    }

    @Test
    fun givenAFailedCapture_whenShown_thenItSaysTheShotWasNotMeasured() {
        show(
            DashboardUiState.Live(
                connected,
                latest = PREVIEW_SHOT,
                previous = emptyList(),
                processing = ProcessingIndicator.of(ShotProcessingState.FAILED),
            ),
        )

        composeRule.onNodeWithTag(DashboardTestTags.PROCESSING).assertIsDisplayed()
        composeRule.onNodeWithText("Shot not measured", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun givenNothingProcessing_whenShown_thenThereIsNoIndicator() {
        show(DashboardUiState.Waiting(connected))

        composeRule.onAllNodes(hasTestTag(DashboardTestTags.PROCESSING)).assertCountEquals(0)
    }

    @Test
    fun givenARejectedAddress_whenShown_thenTheReasonAndFixAreSpelledOut() {
        val link = PiLinkState.Rejected("Public addresses need HTTPS")
        show(
            DashboardUiState.Waiting(
                ConnectionPanelState(
                    transport = TransportType.WIFI,
                    state = ConnectionState.Error("Public addresses need HTTPS", ConnectionErrorKind.ENDPOINT_REJECTED),
                    problem = ConnectionProblem.of(ConnectionState.Idle, link),
                ),
            ),
        )

        composeRule.onNodeWithTag(DashboardTestTags.CONNECTION_PROBLEM).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionProblem.ADDRESS_REJECTED_TITLE, useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        val PREVIEW_SHOT =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10",
                club = "driver",
                ballSpeedMph = 151.4,
                estimatedCarryYards = 264.0,
            )
    }
}
