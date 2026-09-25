// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Plan R8f: the profile picker on the Android dashboard. */
@RunWith(AndroidJUnit4::class)
class DashboardProfilePickerScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<DashboardEvent>()

    private val rows =
        listOf(
            ProfileRow("p1", "Ann", active = true, removeBlockedReason = "Active profile"),
            ProfileRow("p2", "Bob", active = false, removeBlockedReason = null),
        )

    private val wifi =
        ProfilePickerState(
            loaded = true,
            activeName = "Ann",
            rows = rows,
            selection = PiFeatureAvailability.Available,
            edits = PiFeatureAvailability.Available,
        )

    private fun show(profile: ProfilePickerState) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState =
                        DashboardUiState.Waiting(
                            ConnectionPanelState(
                                transport = TransportType.WIFI,
                                state = ConnectionState.Connected,
                                profile = profile,
                            ),
                        ),
                    onEvent = { events += it },
                    onOpenCalibration = {},
                    onOpenRange = {},
                )
            }
        }
    }

    @Test
    fun givenARoster_whenTheProfileIsTapped_thenThePickerOpens() {
        show(wifi)

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_BUTTON).performScrollTo().performClick()

        composeRule.onNodeWithText("Ann · Change").assertIsDisplayed()
        assertEquals(listOf<DashboardEvent>(ProfilePickerEvent.Open), events)
    }

    @Test
    fun givenNoLink_whenShown_thenTheProfileIsDisabledWithTheReason() {
        show(
            ProfilePickerState(
                selection = PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED),
            ),
        )

        composeRule
            .onNodeWithTag(DashboardTestTags.PROFILE_BUTTON)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Not set · Change").assertIsDisplayed()
    }

    @Test
    fun givenTheOpenSheet_whenBobIsPicked_thenItAsksForBob() {
        show(wifi.copy(sheet = ProfileSheet.List))

        composeRule.onNodeWithTag(DashboardTestTags.profileRow("p1")).assertIsSelected()
        composeRule.onNodeWithTag(DashboardTestTags.profileRow("p2")).performClick()

        assertEquals(listOf<DashboardEvent>(ProfilePickerEvent.Select("p2")), events)
    }

    @Test
    fun givenTheActiveProfile_whenShown_thenItCannotBeRemoved() {
        show(wifi.copy(sheet = ProfileSheet.List))

        composeRule.onNodeWithTag(DashboardTestTags.profileRemove("p1")).assertIsNotEnabled()
        composeRule.onNodeWithTag(DashboardTestTags.profileRemove("p2")).performClick()

        assertEquals(listOf<DashboardEvent>(ProfilePickerEvent.Remove("p2")), events)
    }

    @Test
    fun givenBluetoothV2_whenTheSheetIsOpen_thenOnlySelectingIsOffered() {
        show(
            wifi.copy(
                sheet = ProfileSheet.List,
                edits = PiFeatureAvailability.Unavailable(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH),
            ),
        )

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_ADD).assertIsNotEnabled()
        composeRule.onAllNodes(hasTestTag(DashboardTestTags.profileRename("p2"))).assertCountEquals(0)
        composeRule
            .onNodeWithText(
                PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH,
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    @Test
    fun givenTheAddForm_whenANameIsTypedAndSaved_thenItIsSubmitted() {
        show(wifi.copy(sheet = ProfileSheet.Adding("")))

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_NAME_FIELD).performTextInput("Cara")
        assertEquals(listOf<DashboardEvent>(ProfilePickerEvent.NameEdited("Cara")), events)
    }

    @Test
    fun givenABlankNameError_whenShown_thenTheRuleIsSpelledOut() {
        show(wifi.copy(sheet = ProfileSheet.Adding("   ", "Enter a name for the profile.")))

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_FORM_ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_SAVE).assertIsNotEnabled()
    }

    @Test
    fun givenARemovalToConfirm_whenConfirmed_thenItIsSent() {
        show(wifi.copy(sheet = ProfileSheet.ConfirmingRemoval("p2", "Bob")))

        composeRule.onNodeWithText("Remove “Bob”?").assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_REMOVE_CONFIRM).performClick()

        assertEquals(listOf<DashboardEvent>(ProfilePickerEvent.ConfirmRemove), events)
    }

    @Test
    fun givenARefusedRemoval_whenShown_thenTheNoticeSaysUnchanged() {
        show(wifi.copy(notice = "“Bob” is unchanged. ${ProfilePickerState.REFUSED_REMOVAL}"))

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_NOTICE).performScrollTo().assertIsDisplayed()
    }
}
