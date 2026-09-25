// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The session history list and a stored session's detail (plan R8h). */
@RunWith(AndroidJUnit4::class)
class SessionHistoryScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SessionHistoryEvent>()
    private val opened = mutableListOf<String>()
    private val detailEvents = mutableListOf<SessionHistoryDetailEvent>()

    private val twoSessions =
        SessionHistoryUiState(
            loaded = true,
            sessions =
                listOf(
                    SessionHistoryRow(
                        "newer",
                        "Fri 25 Sep",
                        "10:03 – 10:45",
                        24,
                        "Wi-Fi",
                        isCurrent = true,
                        host = "raspberrypi.local:8080",
                        spokenDate = "Friday 25 September 2026",
                    ),
                    SessionHistoryRow("older", "Mon 21 Sep", "18:12", 1, "Bluetooth", isCurrent = false),
                ),
        )

    private fun showList(state: SessionHistoryUiState) {
        composeRule.setContent {
            OfTheme {
                SessionHistoryScreen(
                    uiState = state,
                    onEvent = { events += it },
                    onBack = {},
                    onOpenSession = { opened += it },
                )
            }
        }
    }

    @Test
    fun givenStoredSessions_whenShown_thenEachReadsAsOneSentenceWithItsDayTimesAndSource() {
        showList(twoSessions)

        // One TalkBack stop per row (plan R8f): the day spelled out, the times, the count, the source.
        composeRule
            .onNodeWithTag(SessionHistoryTestTags.session("newer"))
            .assertIsDisplayed()
            .assert(
                hasContentDescription(
                    "Friday 25 September 2026, 10:03 to 10:45, 24 shots, Wi-Fi, " +
                        "raspberrypi.local:8080, current session",
                ),
            )
        composeRule
            .onNodeWithTag(SessionHistoryTestTags.session("older"))
            .assert(hasContentDescription("Mon 21 Sep, 18:12, 1 shot, Bluetooth"))
    }

    @Test
    fun givenStoredSessions_whenOneIsTapped_thenItOpens() {
        showList(twoSessions)

        composeRule.onNodeWithTag(SessionHistoryTestTags.session("older")).performClick()

        assertEquals(listOf("older"), opened)
    }

    @Test
    fun givenNoSessions_whenShown_thenTheEmptyStateShowsWithoutClearAll() {
        showList(SessionHistoryUiState(loaded = true))

        composeRule.onNodeWithTag(SessionHistoryTestTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL).assertDoesNotExist()
    }

    @Test
    fun givenAnUnavailableDatabase_whenShown_thenTheNoteShows() {
        showList(SessionHistoryUiState(loaded = true, isPersistent = false))

        composeRule.onNodeWithTag(SessionHistoryTestTags.NOT_PERSISTENT).assertIsDisplayed()
    }

    @Test
    fun whenClearAllIsTapped_thenTheViewModelIsAskedToConfirm() {
        showList(twoSessions)

        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL).performClick()

        assertEquals(listOf<SessionHistoryEvent>(SessionHistoryEvent.ClearAll), events)
    }

    @Test
    fun givenClearAllToConfirm_whenConfirmedOrCancelled_thenThatIsSent() {
        showList(twoSessions.copy(action = SessionActionCopy.confirmClearAll))

        composeRule.onNodeWithText("Clear all history?").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionActionTestTags.CONFIRM).performClick()

        assertEquals(listOf<SessionHistoryEvent>(SessionHistoryEvent.ConfirmAction), events)
    }

    @Test
    fun givenAPendingClearAll_whenShown_thenItSpinsAndClearAllIsDisabled() {
        val clear = SessionAction.ClearAllHistory
        showList(twoSessions.copy(action = SessionActionState.Pending(clear, SessionActionCopy.pending(clear))))

        composeRule.onNodeWithText("Clearing history…").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL).assertIsNotEnabled()
    }

    @Test
    fun givenAFailedClearAll_whenRetried_thenRetryIsSent() {
        val clear = SessionAction.ClearAllHistory
        showList(
            twoSessions.copy(
                action =
                    SessionActionState.Failed(
                        clear,
                        SessionActionCopy.failedTitle(clear),
                        SessionActionCopy.STORAGE_DID_NOT_RESPOND,
                        canRetry = true,
                    ),
            ),
        )

        composeRule.onNodeWithText("Couldn't clear history").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionActionTestTags.RETRY).performClick()

        assertEquals(listOf<SessionHistoryEvent>(SessionHistoryEvent.RetryAction), events)
    }

    @Test
    fun givenAStoredSession_whenExportIsTapped_thenExportCsvIsSent() {
        composeRule.setContent {
            OfTheme {
                SessionHistoryDetailScreen(
                    uiState =
                        SessionHistoryDetailUiState(
                            loaded = true,
                            title = "Fri 25 Sep",
                            subtitle = "10:03 – 10:45 · 2 shots",
                            sourceLine = "Wi-Fi · raspberrypi.local:8080",
                            session = SessionUiState(allCount = previewRows.size, shots = previewRows),
                        ),
                    onEvent = { detailEvents += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("10:03 – 10:45 · 2 shots").assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                SessionHistoryTestTags.DETAIL_SOURCE,
            ).assert(hasText("Wi-Fi · raspberrypi.local:8080"))
        composeRule.onNodeWithTag(SessionTestTags.STATS).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_EXPORT).performClick()

        assertEquals(listOf<SessionHistoryDetailEvent>(SessionHistoryDetailEvent.ExportCsv), detailEvents)
    }

    private fun showDetail(state: SessionHistoryDetailUiState) {
        composeRule.setContent {
            OfTheme { SessionHistoryDetailScreen(uiState = state, onEvent = { detailEvents += it }, onBack = {}) }
        }
    }

    private val twoProfiles =
        SessionHistoryDetailUiState(
            loaded = true,
            title = "Fri 25 Sep",
            subtitle = "10:03 – 10:45 · 2 shots",
            isCurrent = true,
            profileChips = listOf(HistoryProfileChip("ann", "Ann", 1), HistoryProfileChip("bo", "Bo", 1)),
            session = SessionUiState(allCount = previewRows.size, shots = previewRows),
        )

    @Test
    fun givenTwoProfiles_whenOneIsTapped_thenTheDetailFiltersToIt() {
        showDetail(twoProfiles)

        composeRule.onNodeWithTag(SessionHistoryTestTags.PROFILE_ALL).assertIsSelected()
        composeRule.onNodeWithTag(SessionHistoryTestTags.profile("bo")).performClick()

        assertEquals(listOf<SessionHistoryDetailEvent>(SessionHistoryDetailEvent.SelectProfile("bo")), detailEvents)
        composeRule.onNodeWithTag(SessionHistoryTestTags.CURRENT, useUnmergedTree = true).assertExists()
    }

    @Test
    fun givenAStoredShot_whenSwipedLeft_thenTheViewModelIsAskedToConfirm() {
        showDetail(twoProfiles)
        val row = SessionTestTags.shot(previewRows.first().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))

        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(
            listOf<SessionHistoryDetailEvent>(SessionHistoryDetailEvent.DeleteShot(previewRows.first().id)),
            detailEvents,
        )
        // The row slides back while the confirmation is up.
        composeRule.onNodeWithTag(row).assertIsDisplayed()
    }

    @Test
    fun givenAPendingDelete_whenARowIsSwiped_thenNothingIsSent() {
        val delete = SessionAction.DeleteShot(previewRows.first().id, 2, "7-Iron")
        showDetail(twoProfiles.copy(action = SessionActionState.Pending(delete, SessionActionCopy.pending(delete))))
        composeRule.onNodeWithText("Deleting shot #2…").assertIsDisplayed()
        val row = SessionTestTags.shot(previewRows.last().id)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))

        composeRule.onNodeWithTag(row).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList(), detailEvents)
    }
}
