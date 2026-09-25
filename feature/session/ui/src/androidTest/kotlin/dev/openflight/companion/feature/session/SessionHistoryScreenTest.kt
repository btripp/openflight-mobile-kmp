// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
                    SessionHistoryRow("newer", "2026-09-25", "10:03 – 10:45", 24, "Wi-Fi", isCurrent = true),
                    SessionHistoryRow("older", "2026-09-21", "18:12", 1, "Bluetooth", isCurrent = false),
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
    fun givenStoredSessions_whenShown_thenEachShowsItsDateTimesAndShotCount() {
        showList(twoSessions)

        composeRule.onNodeWithText("2026-09-25").assertIsDisplayed()
        composeRule.onNodeWithText("10:03 – 10:45 · Wi-Fi").assertIsDisplayed()
        composeRule.onNodeWithText("24 shots").assertIsDisplayed()
        composeRule.onNodeWithText("1 shot").assertIsDisplayed()
        composeRule.onNodeWithText("Current").assertIsDisplayed()
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
    fun whenClearAllIsCancelled_thenNothingIsSent() {
        showList(twoSessions)

        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL_CANCEL).performClick()

        assertEquals(emptyList(), events)
    }

    @Test
    fun whenClearAllIsConfirmed_thenClearAllIsSent() {
        showList(twoSessions)

        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.CLEAR_ALL_CONFIRM).performClick()

        assertEquals(listOf<SessionHistoryEvent>(SessionHistoryEvent.ClearAll), events)
    }

    @Test
    fun givenAStoredSession_whenExportIsTapped_thenExportCsvIsSent() {
        composeRule.setContent {
            OfTheme {
                SessionHistoryDetailScreen(
                    uiState =
                        SessionHistoryDetailUiState(
                            loaded = true,
                            title = "2026-09-25",
                            subtitle = "10:03 – 10:45 · 2 shots",
                            session = SessionUiState(allCount = previewRows.size, shots = previewRows),
                        ),
                    onEvent = { detailEvents += it },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("10:03 – 10:45 · 2 shots").assertIsDisplayed()
        composeRule.onNodeWithTag(SessionTestTags.STATS).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_EXPORT).performClick()

        assertEquals(listOf<SessionHistoryDetailEvent>(SessionHistoryDetailEvent.ExportCsv), detailEvents)
    }
}
