// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.openflight.companion.core.insights.DispersionArc
import dev.openflight.companion.core.insights.DispersionViewport
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

    /** Asserts a tile's spoken description: its label spelled out, then its value and unit. */
    private fun assertStat(
        label: String,
        spoken: String,
    ) {
        composeRule.onNodeWithTag(SessionTestTags.stat(label)).assert(hasContentDescription(spoken))
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
        assertStat("Shots", "Shots, 2")
        assertStat("Avg Ball (mph)", "Average ball speed, 134.8 mph")
        assertStat("Max Ball (mph)", "Maximum ball speed, 151.4 mph")
        assertStat("Avg Carry (yds)", "Average carry, 215 yds")
        // No club speed or smash was reported: an em dash, like the kiosk (Expo `stats.tsx`).
        assertStat("Avg Club (mph)", "Average club speed, not available")
    }

    @Test
    fun givenShots_whenShown_thenMinimumAndStandardDeviationAreShown() {
        show(withShots.copy(stats = withShots.stats.copy(minBallSpeedMph = 118.2, stdDevBallSpeedMph = 23.4)))

        assertStat("Min Ball (mph)", "Minimum ball speed, 118.2 mph")
        assertStat("Ball Std Dev (mph)", "Ball speed standard deviation, 23.4 mph")
    }

    @Test
    fun givenMetricUnits_whenShown_thenStatsAreConverted() {
        show(withShots.copy(units = UnitSystem.METRIC))

        assertStat("Max Ball (km/h)", "Maximum ball speed, 243.7 km/h")
        assertStat("Avg Carry (m)", "Average carry, 196 m")
    }

    @Test
    fun givenASwingSession_whenShown_thenSwingTilesReplaceBallStats() {
        val swing = SwingSpeedStats(count = 3, lastSpeedMph = 101.0, bestSpeedMph = 108.4, avgSpeedMph = 104.0)
        show(withShots.copy(swingStats = swing))

        assertStat("Swings", "Swings, 3")
        assertStat("Best (mph)", "Best swing speed, 108.4 mph")
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
    fun whenClearIsTapped_thenTheViewModelIsAskedToConfirm() {
        show(withShots)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.CLEAR))

        composeRule.onNodeWithTag(SessionTestTags.CLEAR).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.ClearHistory), events)
    }

    // region destructive action states (plan R8f)

    private val clearAnn = SessionAction.ClearSession("ann", "Ann")

    @Test
    fun givenAClearToConfirm_whenConfirmed_thenConfirmActionIsSent() {
        show(withShots.copy(action = SessionActionCopy.confirmClear(clearAnn)))

        composeRule.onNodeWithText("Clear Ann's session?").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionActionTestTags.CONFIRM).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.ConfirmAction), events)
    }

    @Test
    fun givenAClearToConfirm_whenCancelled_thenCancelActionIsSent() {
        show(withShots.copy(action = SessionActionCopy.confirmClear(clearAnn)))

        composeRule.onNodeWithTag(SessionActionTestTags.CANCEL).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.CancelAction), events)
    }

    @Test
    fun givenAPendingClear_whenShown_thenItSpinsAndEditingIsDisabled() {
        show(withShots.copy(action = SessionActionState.Pending(clearAnn, SessionActionCopy.pending(clearAnn))))

        composeRule.onNodeWithTag(SessionActionTestTags.PENDING).assertIsDisplayed()
        composeRule.onNodeWithText("Clearing Ann's session…").assertIsDisplayed()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.CLEAR))
        composeRule.onNodeWithTag(SessionTestTags.CLEAR).assertIsNotEnabled().performClick()
        val row = SessionTestTags.shot(previewRows.first().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // No double submit: neither another clear nor a delete gets through.
        assertEquals(
            emptyList<SessionEvent>(),
            events.filter { it is SessionEvent.ClearHistory || it is SessionEvent.DeleteShot },
        )
    }

    @Test
    fun givenAFailedClear_whenRetryIsTapped_thenRetryActionIsSent() {
        show(
            withShots.copy(
                action =
                    SessionActionState.Failed(
                        clearAnn,
                        SessionActionCopy.failedTitle(clearAnn),
                        "The Pi didn't confirm the clear.",
                        canRetry = true,
                    ),
            ),
        )

        composeRule.onNodeWithText("Clear not confirmed").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionActionTestTags.RETRY).performClick()
        composeRule.onNodeWithTag(SessionActionTestTags.DISMISS).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.RetryAction, SessionEvent.DismissAction), events)
    }

    @Test
    fun givenAFailureThatCantBeRetried_whenShown_thenOnlyDismissIsOffered() {
        show(
            withShots.copy(
                action =
                    SessionActionState.Failed(
                        clearAnn,
                        SessionActionCopy.failedTitle(clearAnn),
                        "The connection dropped. ${SessionActionCopy.RECONNECT_TO_RETRY}",
                        canRetry = false,
                    ),
            ),
        )

        composeRule.onAllNodes(hasTestTag(SessionActionTestTags.RETRY)).assertCountEquals(0)
        composeRule.onNodeWithTag(SessionActionTestTags.DISMISS).assertIsDisplayed()
    }

    @Test
    fun givenADoneDelete_whenOkIsTapped_thenItIsDismissed() {
        val delete = SessionAction.DeleteShot(previewRows.first().id, 2, "7-Iron")
        show(withShots.copy(action = SessionActionState.Done(delete, SessionActionCopy.done(delete))))

        composeRule.onNodeWithText("Shot #2 deleted.").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionActionTestTags.DISMISS).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.DismissAction), events)
    }

    @Test
    fun givenNothingConnected_whenShown_thenTheStaleNoteShows() {
        show(withShots.copy(staleNote = SessionUiState.STALE_NOTE))

        composeRule
            .onNodeWithTag(SessionActionTestTags.STALE_NOTE)
            .assertIsDisplayed()
            .assert(hasText("Not connected — showing the last session received."))
    }

    // endregion

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
            .assert(hasText(PiFeatureAvailability.DELETE_AND_CLEAR_NEED_WIFI))
        val row = SessionTestTags.shot(previewRows.first().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // With no swipe-to-delete, a drag that stays on the row reads as a tap (selecting it on
        // the chart, which Bluetooth allows). What matters is that nothing is deleted.
        assertEquals(emptyList<SessionEvent>(), events.filterIsInstance<SessionEvent.DeleteShot>())
    }

    private val withChart =
        withShots.copy(
            dispersion =
                SessionDispersionUiState(
                    points =
                        listOf(
                            DispersionPoint(previewRows[0].id, 2, "7-iron", "7i", 1, 165.0, -3.0, sideEstimated = true),
                            DispersionPoint(previewRows[1].id, 1, "driver", "D", 0, 264.0, 6.0, sideEstimated = false),
                        ),
                    ellipses = emptyList(),
                    viewport =
                        DispersionViewport(
                            minCarryYards = 150.0,
                            maxCarryYards = 280.0,
                            halfWidthYards = 20.0,
                            arcs = listOf(DispersionArc(200.0, 200), DispersionArc(250.0, 250)),
                        ),
                    estimatedSideCount = 1,
                ),
        )

    private val selectedDriver =
        SelectedShotCard(
            id = previewRows[1].id,
            shotNumber = 1,
            clubName = "Driver",
            carryYards = 264.0,
            spinRpm = 2439.0,
            clubSpeedMph = 112.1,
        )

    @Test
    fun givenShotsOnTheChart_whenShown_thenTheDispersionCardDescribesThem() {
        show(withChart)

        composeRule
            .onNodeWithContentDescription("Dispersion chart, 2 shots: 7-Iron, Driver", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(estimatedCaption(1)).assertIsDisplayed()
    }

    @Test
    fun givenNoPlottableShots_whenShown_thenTheChartIsHidden() {
        show(withShots)

        composeRule.onAllNodes(hasTestTag(SessionTestTags.DISPERSION)).assertCountEquals(0)
    }

    @Test
    fun givenAShot_whenItsRowIsTapped_thenItIsSelectedOnTheChart() {
        show(withChart)
        val row = SessionTestTags.shot(previewRows[1].id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))

        composeRule.onNodeWithTag(row).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.SelectShot(previewRows[1].id)), events)
    }

    @Test
    fun givenASelection_whenShown_thenTheCardShowsCarrySpinAndClubSpeed() {
        show(withChart.copy(selectedShot = selectedDriver))
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.SELECTED))

        composeRule.onNodeWithText("Shot 1 · Driver").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Carry, 264", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Spin, 2,439", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Club speed, 112.1", substring = true).assertIsDisplayed()
        val row = SessionTestTags.shot(previewRows[1].id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        composeRule
            .onNode(isSelected() and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun givenASelection_whenTheCardIsClosed_thenTheSelectionIsCleared() {
        show(withChart.copy(selectedShot = selectedDriver))
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.SELECTED_CLOSE))

        composeRule.onNodeWithTag(SessionTestTags.SELECTED_CLOSE).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.SelectShot(null)), events)
    }

    @Test
    fun givenASelection_whenDeleteIsTappedOnTheCard_thenThatShotIsDeleted() {
        show(withChart.copy(selectedShot = selectedDriver))
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.SELECTED_DELETE))

        composeRule.onNodeWithTag(SessionTestTags.SELECTED_DELETE).performClick()

        assertEquals(listOf<SessionEvent>(SessionEvent.DeleteShot(previewRows[1].id)), events)
    }

    @Test
    fun givenAClubSpread_whenShown_thenItIsSummarisedUnderTheChart() {
        val spread = ClubSpread("7-iron", "7-Iron", 5, 162.0, 1.4, 9.2, 12.0, excludedCount = 1)
        show(withChart.copy(dispersion = withChart.dispersion!!.copy(clubSpread = spread, possibleBadReadCount = 1)))

        composeRule
            .onNodeWithTag(
                SessionTestTags.SPREAD,
            ).assertTextEquals(DispersionCopy.spreadSummary(spread, UnitSystem.IMPERIAL))
        composeRule.onNodeWithText(DispersionCopy.badReadCaption(1)).assertIsDisplayed()
    }

    @Test
    fun givenAPossibleBadRead_whenSelected_thenTheCardSaysSo() {
        show(withChart.copy(selectedShot = selectedDriver.copy(possibleBadRead = true)))
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.BAD_READ_NOTE))

        composeRule.onNodeWithTag(SessionTestTags.BAD_READ_NOTE).assertIsDisplayed()
    }

    @Test
    fun givenBluetoothAndASelection_whenShown_thenTheCardsDeleteIsDisabled() {
        show(
            withChart.copy(
                selectedShot = selectedDriver,
                editAvailability = PiFeatureAvailability.forDeleteAndClear(overBluetooth = true),
            ),
        )
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(SessionTestTags.SELECTED_DELETE))

        composeRule.onNodeWithTag(SessionTestTags.SELECTED_DELETE).assertIsNotEnabled().performClick()

        assertEquals(emptyList<SessionEvent>(), events)
    }
}
