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
        composeRule
            .onNode(isToggleable() and hasAnyAncestor(hasTestTag(SettingsTestTags.DEBUG_TOGGLE)))
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.CLOUD_UPLOAD).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.SHUTDOWN).performScrollTo().assertIsNotEnabled()
        // Profile, simulators, radar, debug, cloud and shutdown each explain why.
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

    private companion object {
        /** Player, simulators, radar, debug, cloud upload and shutdown. */
        const val WIFI_ONLY_SECTIONS = 6
    }
}
