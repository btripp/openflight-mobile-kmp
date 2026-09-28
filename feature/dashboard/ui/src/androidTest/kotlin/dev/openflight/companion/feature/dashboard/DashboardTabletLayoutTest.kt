// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plan F1b: an expanded window shows the live metrics and the connection card side by side. */
@RunWith(AndroidJUnit4::class)
class DashboardTabletLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        state: DashboardUiState,
        windowClass: OfWindowClass,
    ) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState = state,
                    onEvent = {},
                    onOpenCalibration = {},
                    onOpenRange = {},
                    windowClass = windowClass,
                )
            }
        }
    }

    @Test
    fun givenAnExpandedWindow_whenShown_thenMetricsAndConnectionAreSideBySideColumns() {
        show(
            DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), bareShot, emptyList()),
            windowClass = OfWindowClass.EXPANDED,
        )

        composeRule.onNodeWithTag(DashboardTestTags.METRICS_COLUMN).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.CONNECTION_COLUMN).assertIsDisplayed()
        // The connection card (its transport picker) is in the right column only, not repeated.
        composeRule
            .onAllNodes(
                hasText("Network") and hasAnyAncestor(hasTestTag(DashboardTestTags.CONNECTION_COLUMN)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule
            .onAllNodes(
                hasText("Network") and hasAnyAncestor(hasTestTag(DashboardTestTags.METRICS_COLUMN)),
                useUnmergedTree = true,
            ).assertCountEquals(0)
    }

    @Test
    fun givenACompactWindow_whenShown_thenThereIsOnlyOneStackedColumn() {
        show(
            DashboardUiState.Live(ConnectionPanelState(state = ConnectionState.Connected), bareShot, emptyList()),
            windowClass = OfWindowClass.COMPACT,
        )

        composeRule.onAllNodes(hasTestTag(DashboardTestTags.METRICS_COLUMN)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(DashboardTestTags.CONNECTION_COLUMN)).assertCountEquals(0)
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    private val bareShot =
        ShotEvent(
            schemaVersion = 1,
            eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
            timestamp = "2026-07-29T19:42:10",
            club = "driver",
            ballSpeedMph = 151.4,
            clubSpeedMph = null,
            smashFactor = null,
            estimatedCarryYards = 264.0,
            launchAngleVertical = null,
            launchAngleHorizontal = null,
            spinRpm = null,
            clubPathDeg = null,
            spinAxisDeg = null,
        )
}
