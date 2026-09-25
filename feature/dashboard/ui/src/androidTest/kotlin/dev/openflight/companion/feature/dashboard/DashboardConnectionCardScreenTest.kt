// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.ConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Plan R8d on the Android connection card: help links, host hints and the club confirmation. */
@RunWith(AndroidJUnit4::class)
class DashboardConnectionCardScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<DashboardEvent>()
    private val openedUris = mutableListOf<String>()

    private fun show(panel: ConnectionPanelState) {
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUris += uri
                }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                OfTheme {
                    DashboardScreen(
                        uiState = DashboardUiState.Waiting(panel),
                        onEvent = { events += it },
                        onOpenCalibration = {},
                        onOpenRange = {},
                    )
                }
            }
        }
    }

    @Test
    fun givenNoConnection_whenTheHelpLinkIsTapped_thenTheBuildGuideOpens() {
        show(ConnectionPanelState(state = ConnectionState.Idle))

        composeRule.onNodeWithTag(DashboardTestTags.HELP_LINK).performScrollTo().performClick()

        assertEquals(listOf(ConnectionHelpLink.BUILD_GUIDE.url), openedUris)
    }

    @Test
    fun givenAFailedConnection_whenTheHelpLinkIsTapped_thenTroubleshootingOpens() {
        show(ConnectionPanelState(state = ConnectionState.Error("refused")))

        composeRule
            .onNodeWithTag(DashboardTestTags.HELP_LINK)
            .performScrollTo()
            .assertTextContains(ConnectionHelpLink.TROUBLESHOOTING.label)
            .performClick()

        assertEquals(listOf(ConnectionHelpLink.TROUBLESHOOTING.url), openedUris)
    }

    @Test
    fun givenAConnection_whenShown_thenThereIsNoHelpLink() {
        show(ConnectionPanelState(state = ConnectionState.Connected))

        composeRule.onNodeWithTag(DashboardTestTags.HELP_LINK).assertDoesNotExist()
    }

    @Test
    fun givenWifi_whenTheAccessPointHintIsTapped_thenItFillsTheHost() {
        show(ConnectionPanelState(transport = TransportType.WIFI))

        composeRule.onNodeWithTag(DashboardTestTags.hostHint("192.168.4.1:8080")).performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.HostHintSelected("192.168.4.1:8080")), events)
        composeRule.onNodeWithTag(DashboardTestTags.hostHint("192.168.1.100:8080")).assertIsDisplayed()
    }

    @Test
    fun givenBluetooth_whenShown_thenThereAreNoHostHints() {
        show(ConnectionPanelState(transport = TransportType.BLUETOOTH))

        composeRule.onNodeWithTag(DashboardTestTags.hostHint("192.168.4.1:8080")).assertDoesNotExist()
    }

    @Test
    fun givenTheClubConfirmation_whenLooksRightIsTapped_thenItIsConfirmed() {
        show(ConnectionPanelState(state = ConnectionState.Connected, showClubConfirmation = true))

        composeRule.onNodeWithTag(DashboardTestTags.CLUB_CONFIRMATION).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.CLUB_CONFIRM).performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.ClubConfirmed), events)
    }

    @Test
    fun givenNoPendingConfirmation_whenShown_thenThePromptIsHidden() {
        show(ConnectionPanelState(state = ConnectionState.Connected))

        composeRule.onNodeWithTag(DashboardTestTags.CLUB_CONFIRMATION).assertDoesNotExist()
    }
}
