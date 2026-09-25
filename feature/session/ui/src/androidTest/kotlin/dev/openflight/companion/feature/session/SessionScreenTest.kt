// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SessionScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SessionEvent>()

    private fun show(state: SessionUiState) {
        composeRule.setContent { OfTheme { SessionScreen(uiState = state, onEvent = { events += it }, onBack = {}) } }
    }

    private fun assertStat(
        label: String,
        value: String,
    ) {
        composeRule.onNodeWithTag(SessionTestTags.stat(label)).assert(hasContentDescription("$label, $value"))
    }

    private val withShots =
        SessionUiState(
            source = SessionSource.LOCAL,
            allCount = 2,
            clubChips = listOf(ClubChip("driver", 1), ClubChip("7-iron", 1)),
            stats =
                ClubStats(
                    shotCount = 2,
                    avgBallSpeedMph = 134.8,
                    maxBallSpeedMph = 151.4,
                    avgCarryYards = 214.6,
                    avgClubSpeedMph = null,
                    avgSmashFactor = null,
                ),
            shots = previewRows,
        )

    @Test
    fun givenNoShots_whenShown_thenTheEmptyStateShowsWithoutActions() {
        show(SessionUiState())

        composeRule.onNodeWithTag(SessionTestTags.EMPTY).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SessionTestTags.EXPORT)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(SessionTestTags.CLEAR)).assertCountEquals(0)
    }

    @Test
    fun givenShots_whenShown_thenStatTilesShowTheSelectedTabsStats() {
        show(withShots)

        composeRule.onNodeWithTag(SessionTestTags.ALL_TAB).assertIsSelected()
        assertStat("Shots", "2")
        assertStat("Avg Ball (mph)", "134.8")
        assertStat("Max Ball (mph)", "151.4")
        assertStat("Avg Carry (yds)", "215")
        // No club speed or smash was reported, so those tiles are left out (StatsView.tsx).
        composeRule.onAllNodes(hasTestTag(SessionTestTags.stat("Avg Club (mph)"))).assertCountEquals(0)
    }

    @Test
    fun givenMetricUnits_whenShown_thenStatsAreConverted() {
        show(withShots.copy(units = UnitSystem.METRIC))

        assertStat("Max Ball (km/h)", "243.7")
        assertStat("Avg Carry (m)", "196")
    }

    @Test
    fun givenASwingSession_whenShown_thenSwingTilesReplaceBallStats() {
        val swing = SwingSpeedStats(count = 3, lastSpeedMph = 101.0, bestSpeedMph = 108.4, avgSpeedMph = 104.0)
        show(withShots.copy(swingStats = swing))

        assertStat("Swings", "3")
        assertStat("Best (mph)", "108.4")
        composeRule.onAllNodes(hasTestTag(SessionTestTags.stat("Shots"))).assertCountEquals(0)
    }

    @Test
    fun givenShots_whenAClubTabIsTapped_thenThatClubIsSelected() {
        show(withShots)

        composeRule.onNodeWithTag(SessionTestTags.tab("7-iron")).performClick()
        composeRule.onNodeWithTag(SessionTestTags.ALL_TAB).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.SelectClub("7-iron"), SessionEvent.SelectClub(null)), events)
    }

    @Test
    fun givenAShot_whenSwipedLeft_thenItIsDeleted() {
        show(withShots)
        val row = SessionTestTags.shot(previewRows.first().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))

        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(listOf<SessionEvent>(SessionEvent.DeleteShot(previewRows.first().id)), events)
    }

    @Test
    fun whenClearIsTappedAndConfirmed_thenTheHistoryIsCleared() {
        show(withShots)

        composeRule.onNodeWithTag(SessionTestTags.CLEAR).performClick()
        composeRule.onNodeWithText("Clear session?").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.CLEAR_CONFIRM).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.ClearHistory), events)
        composeRule.onAllNodes(hasTestTag(SessionTestTags.CLEAR_CONFIRM)).assertCountEquals(0)
    }

    @Test
    fun whenClearIsTappedAndCancelled_thenNothingIsCleared() {
        show(withShots)

        composeRule.onNodeWithTag(SessionTestTags.CLEAR).performClick()
        composeRule.onNodeWithTag(SessionTestTags.CLEAR_CANCEL).performClick()

        assertEquals(emptyList(), events)
        composeRule.onAllNodes(hasTestTag(SessionTestTags.CLEAR_CONFIRM)).assertCountEquals(0)
    }

    @Test
    fun whenExportIsTapped_thenTheCsvIsRequested() {
        show(withShots)

        composeRule.onNodeWithTag(SessionTestTags.EXPORT).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.ExportCsv), events)
    }

    @Test
    fun givenAMockPiSession_whenSimulateIsTapped_thenAShotIsSimulated() {
        show(
            withShots.copy(
                source = SessionSource.PI,
                showSimulateShot = true,
                simulateAvailability = PiFeatureAvailability.Available,
            ),
        )

        composeRule.onNodeWithText("Pi session").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.SIMULATE).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.SimulateShot), events)
    }

    @Test
    fun givenSimulateWithoutTheLink_whenShown_thenItIsDisabledWithTheReason() {
        show(
            withShots.copy(
                showSimulateShot = true,
                simulateAvailability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED),
            ),
        )

        composeRule.onNodeWithText("This phone").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.SIMULATE).assertIsNotEnabled()
        composeRule.onNodeWithText(PiFeatureAvailability.NOT_CONNECTED).assertIsDisplayed()
    }

    @Test
    fun givenNoMockMode_whenShown_thenSimulateIsHidden() {
        show(withShots)

        composeRule.onAllNodes(hasTestTag(SessionTestTags.SIMULATE)).assertCountEquals(0)
    }

    // Plan R8e: Bluetooth is read-and-select, so delete and clear are disabled with a reason.
    @Test
    fun givenBluetooth_whenShown_thenClearIsDisabledWithAWifiOnlyReasonAndSwipingDeletesNothing() {
        show(
            withShots.copy(
                editAvailability = PiFeatureAvailability.forDeleteAndClear(overBluetooth = true),
            ),
        )
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.CLEAR))

        composeRule.onNodeWithTag(SessionTestTags.CLEAR).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(SessionTestTags.EDIT_DISABLED_REASON)
            .assertIsDisplayed()
            .assert(hasText(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH))
        val row = SessionTestTags.shot(previewRows.first().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList<SessionEvent>(), events)
    }
}
