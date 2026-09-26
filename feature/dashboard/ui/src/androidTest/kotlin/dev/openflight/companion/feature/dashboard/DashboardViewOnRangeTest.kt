// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Plan F8d: Practice's latest shot opens on the range by its event id, on phones and tablets. */
@RunWith(AndroidJUnit4::class)
class DashboardViewOnRangeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val latest =
        ShotEvent(
            schemaVersion = 1,
            eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
            timestamp = "2026-09-25T11:00:00",
            club = "driver",
            ballSpeedMph = 151.4,
            estimatedCarryYards = 264.0,
        )

    private fun show(
        windowClass: OfWindowClass,
        onViewOnRange: ((String) -> Unit)?,
    ) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState =
                        DashboardUiState.Live(
                            ConnectionPanelState(state = ConnectionState.Connected),
                            latest,
                            emptyList(),
                        ),
                    onEvent = {},
                    onOpenCalibration = {},
                    onOpenRange = {},
                    windowClass = windowClass,
                    onViewOnRange = onViewOnRange,
                )
            }
        }
    }

    @Test
    fun given_latestShot_when_viewOnRange_then_itsEventIdOpens() {
        val viewed = mutableListOf<String>()
        show(OfWindowClass.COMPACT) { viewed += it }

        composeRule.onNodeWithTag(DashboardTestTags.VIEW_ON_RANGE).assertIsDisplayed().performClick()

        assertEquals(listOf(latest.eventId), viewed)
    }

    @Test
    fun given_expanded_when_viewOnRange_then_itsEventIdOpens() {
        val viewed = mutableListOf<String>()
        show(OfWindowClass.EXPANDED) { viewed += it }

        composeRule.onNodeWithTag(DashboardTestTags.VIEW_ON_RANGE).assertIsDisplayed().performClick()

        assertEquals(listOf(latest.eventId), viewed)
    }

    @Test
    fun given_noNavigation_when_shown_then_noViewOnRange() {
        show(OfWindowClass.COMPACT, onViewOnRange = null)

        composeRule.onNodeWithTag(DashboardTestTags.LATEST_SHOT).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.VIEW_ON_RANGE).assertDoesNotExist()
    }
}
