// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.insights.ShotDetailBuilder
import dev.openflight.companion.core.insights.ShotDetailTestTags
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.toShotDetailInput
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Practice's shots open the shot detail sheet: the latest card's Details and a previous shot's row. */
@RunWith(AndroidJUnit4::class)
class DashboardShotDetailTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(
        id: Int,
        club: String,
    ) = ShotEvent(
        schemaVersion = 1,
        eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E$id",
        timestamp = "2026-09-25T11:00:0$id",
        club = club,
        ballSpeedMph = 151.4,
        estimatedCarryYards = 264.0,
    )

    private val latest = shot(2, "driver")
    private val previous = shot(1, "7-iron")
    private val events = mutableListOf<DashboardEvent>()

    private fun show(
        windowClass: OfWindowClass = OfWindowClass.COMPACT,
        open: ShotEvent? = null,
    ) {
        val detail =
            open?.let {
                ShotDetailBuilder().build(
                    it.toShotDetailInput(shotNumber = 2),
                    Conditions.ISA,
                    null,
                    UnitSystem.IMPERIAL,
                )
            }
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState =
                        DashboardUiState.Live(
                            ConnectionPanelState(state = ConnectionState.Connected),
                            latest,
                            listOf(previous),
                            shotDetail = detail,
                        ),
                    onEvent = { events += it },
                    onOpenCalibration = {},
                    onOpenRange = {},
                    windowClass = windowClass,
                )
            }
        }
    }

    @Test
    fun given_latestShot_when_detailsTapped_then_itsSheetIsRequested() {
        show()

        composeRule.onNodeWithTag(DashboardTestTags.OPEN_SHOT_DETAIL).performScrollTo().performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.OpenShotDetail(latest.eventId)), events)
    }

    @Test
    fun given_previousShot_when_rowTapped_then_itsSheetIsRequested() {
        show()

        composeRule.onNodeWithTag(DashboardTestTags.previousShot(previous.eventId)).performScrollTo().performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.OpenShotDetail(previous.eventId)), events)
    }

    @Test
    fun given_expanded_when_detailsTapped_then_itsSheetIsRequested() {
        show(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(DashboardTestTags.OPEN_SHOT_DETAIL).performScrollTo().performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.OpenShotDetail(latest.eventId)), events)
    }

    @Test
    fun given_anOpenDetail_when_closed_then_closeIsSent() {
        show(open = latest)

        composeRule.onNodeWithTag(ShotDetailTestTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(ShotDetailTestTags.CLOSE).performClick()

        assertEquals(listOf<DashboardEvent>(DashboardEvent.CloseShotDetail), events)
    }
}
