// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SettingsEvent>()

    private fun show(state: SettingsUiState) {
        composeRule.setContent { OfTheme { SettingsScreen(uiState = state, onEvent = { events += it }, onBack = {}) } }
    }

    @Test
    fun whenMetricIsPicked_thenTheUnitsAreSaved() {
        show(previewSettingsState())

        composeRule.onNodeWithText("Metric (km/h, m)").performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.SetUnits(UnitSystem.METRIC)), events)
    }

    @Test
    fun givenBluetooth_whenShown_thenEveryWifiOnlyControlIsDisabledWithTheReason() {
        show(previewSettingsState(link = PiLinkState.WifiOnly, transport = TransportType.BLUETOOTH))

        composeRule.onNodeWithTag(SettingsTestTags.PROFILE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.RADAR_REFRESH).performScrollTo().assertIsNotEnabled()
        // Debug mode is unknown over Bluetooth, so its card isn't offered at all.
        composeRule.onAllNodes(hasTestTag(SettingsTestTags.DEBUG_TOGGLE)).assertCountEquals(0)
        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_UPLOAD).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN).performScrollTo().assertIsNotEnabled()
        // Profile, launch monitor, simulators, radar, cloud and shutdown each explain why.
        composeRule
            .onAllNodes(hasText(PiFeatureAvailability.REQUIRES_WIFI), useUnmergedTree = true)
            .assertCountEquals(WIFI_ONLY_SECTIONS)
        // Units still work on any transport.
        composeRule.onNodeWithText("Metric (km/h, m)").performScrollTo().performClick()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.SetUnits(UnitSystem.METRIC)), events)
    }

    @Test
    fun givenTheLink_whenShown_thenTheActiveProfileShows() {
        show(previewSettingsState())

        composeRule.onNodeWithText("Alex").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun givenTheLink_whenShown_thenConnectionSimulatorsAndSlidersShow() {
        show(previewSettingsState())

        composeRule.onNodeWithText("10.0.2.2:8098").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.simulator("gspro")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("GSPro · connected").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.slider(RadarField.MIN_SPEED)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("10 mph").assertIsDisplayed()
        composeRule.onNodeWithText("Shot detected").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun givenDebugOn_whenShown_thenTheLogPathShowsAndTheToggleSendsToggleDebug() {
        show(previewSettingsState(debugEnabled = true))

        composeRule.onNodeWithTag(SettingsTestTags.DEBUG_LOG_PATH).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("/tmp/openflight_debug.jsonl").assertIsDisplayed()
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(SettingsTestTags.DEBUG_TOGGLE))).performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.ToggleDebug), events)
    }

    @Test
    fun givenTheLink_whenUploadIsTapped_thenTheSessionIsUploaded() {
        show(previewSettingsState())

        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_UPLOAD).performScrollTo().performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.UploadCloud), events)
    }

    @Test
    fun givenTheLink_whenShutDownIsTapped_thenConfirmationIsRequested() {
        show(previewSettingsState())

        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN).performScrollTo().performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.RequestShutdown), events)
        composeRule.onAllNodes(hasTestTag(SettingsTestTags.SHUTDOWN_CONFIRM)).assertCountEquals(0)
    }

    @Test
    fun givenTheConfirmation_whenCancelled_thenTheShutdownIsCancelled() {
        show(previewSettingsState(confirmingShutdown = true))

        composeRule.onNodeWithText(ShutdownSettings.CONFIRMATION_TEXT).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_CANCEL).performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.CancelShutdown), events)
    }

    @Test
    fun givenTheConfirmation_whenConfirmed_thenThePiShutsDown() {
        show(previewSettingsState(confirmingShutdown = true))

        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_CONFIRM).performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.ConfirmShutdown), events)
    }

    @Test
    fun givenAPowerStatus_whenShown_thenTheLabelChargeAndVoltsShow() {
        show(previewSettingsState())

        composeRule.onNodeWithTag(SettingsTestTags.POWER).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("On battery").assertIsDisplayed()
        composeRule.onNodeWithText("78%").assertIsDisplayed()
        composeRule.onNodeWithText("3.91 V").assertIsDisplayed()
    }

    @Test
    fun givenNoPowerStatus_whenShown_thenThereIsNoPowerCard() {
        show(previewSettingsState(power = null))

        composeRule.onAllNodes(hasTestTag(SettingsTestTags.POWER)).assertCountEquals(0)
    }

    @Test
    fun givenATriggerStatus_whenShown_thenTheLaunchMonitorRowsShow() {
        show(previewSettingsState())

        composeRule.onNodeWithTag(SettingsTestTags.TRIGGER).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rolling buffer").assertIsDisplayed()
        composeRule.onNodeWithText("/dev/ttyUSB0").assertIsDisplayed()
        composeRule.onNodeWithText("Triggers seen").assertIsDisplayed()
    }

    @Test
    fun givenNoTriggerStatusYet_whenShown_thenTheCardWaitsForThePi() {
        show(previewSettingsState(trigger = null))

        composeRule.onNodeWithTag(SettingsTestTags.TRIGGER_WAITING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(TriggerCard.WAITING_TEXT).assertIsDisplayed()
    }

    @Test
    fun givenARejectedAddress_whenShown_thenTheReasonIsSpelledOut() {
        show(previewSettingsState(link = PiLinkState.Rejected("Public addresses need HTTPS")))

        composeRule.onNodeWithTag(SettingsTestTags.CONNECTION_PROBLEM).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ConnectionProblem.ADDRESS_REJECTED_TITLE, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun givenAPendingStop_whenShown_thenItSaysStopping() {
        show(previewSettingsState(shutdownPhase = ShutdownPhase.Pending("10.0.2.2:8098")))

        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_PENDING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ShutdownPhase.PENDING_TEXT, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SettingsTestTags.SHUTDOWN)).assertCountEquals(0)
    }

    @Test
    fun givenAStoppedServer_whenShown_thenThePiStaysOnAndOkDismisses() {
        show(previewSettingsState(shutdownPhase = ShutdownPhase.Done("10.0.2.2:8098")))

        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_DONE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ShutdownPhase.DONE_TITLE, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_DISMISS).performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.DismissShutdown), events)
    }

    @Test
    fun givenAFailedStop_whenTryAgainIsTapped_thenItRetries() {
        show(previewSettingsState(shutdownPhase = ShutdownPhase.Failed("10.0.2.2:8098", ShutdownPhase.TIMED_OUT)))

        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_FAILED).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ShutdownPhase.FAILED_TITLE, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN_RETRY).performScrollTo().performClick()

        assertEquals(listOf<SettingsEvent>(SettingsEvent.RetryShutdown), events)
    }

    private companion object {
        /** Profile, launch monitor, simulators, radar, cloud upload and shutdown. */
        const val WIFI_ONLY_SECTIONS = 6
    }
}
