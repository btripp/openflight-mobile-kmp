// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotHistoryRepository
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Plan F1b: on an expanded window, [SessionHistoryRoute] opens a tapped session in a docked detail
 * pane beside the list instead of pushing it as its own destination. Drives the real
 * [SessionHistoryViewModel] and [SessionHistoryDetailViewModel] over [FakeShotHistoryRepository],
 * like `feature:range:ui`'s route tests.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryTabletLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val history = FakeShotHistoryRepository()
    private val settings = FakeSettingsRepository()
    private lateinit var koin: Koin

    @Suppress("DEPRECATION") // KoinContext: see AppNavigationFlowTest for why it's still needed here.
    private fun show(windowClass: OfWindowClass) {
        composeRule.setContent {
            KoinContext(koin) {
                OfTheme {
                    SessionHistoryRoute(
                        onBack = {},
                        onOpenSession = {},
                        onShareCsv = { _, _ -> },
                        windowClass = windowClass,
                    )
                }
            }
        }
    }

    @Before
    fun startTestKoin() {
        runCatching { stopKoin() }
        koin =
            startKoin {
                modules(
                    sessionModule,
                    module {
                        single<ShotHistoryRepository> { history }
                        single<SettingsRepository> { settings }
                    },
                )
            }.koin
        history.put("s1", listOf(stored(1, "2026-09-25T10:03:00")))
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun givenAnExpandedWindow_whenNoSessionIsSelected_thenTheDetailPaneShowsAPlaceholder() {
        show(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SessionHistoryTestTags.session("s1")).assertIsDisplayed()
        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_EMPTY).assertIsDisplayed()
    }

    @Test
    fun givenAnExpandedWindow_whenASessionIsTapped_thenItsDetailShowsInTheDockedPaneOnly() {
        show(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(SessionHistoryTestTags.session("s1")).performClick()

        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_PANE).assertIsDisplayed()
        // The shot row's own timestamp: unique to the row, unlike "Driver" which the club tab
        // chip repeats.
        composeRule
            .onAllNodes(hasText("10:03:00") and hasAnyAncestor(hasTestTag(SessionHistoryTestTags.DETAIL_PANE)))
            .assertCountEquals(1)
        // Closing the pane goes back to the placeholder, not a pushed screen.
        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_BACK).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.DETAIL_EMPTY).assertIsDisplayed()
    }

    @Test
    fun givenACompactWindow_whenShown_thenThereIsNoDockedDetailPane() {
        show(OfWindowClass.COMPACT)

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasTestTag(SessionHistoryTestTags.session("s1")))
        composeRule.onAllNodes(hasTestTag(SessionHistoryTestTags.DETAIL_PANE)).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(SessionHistoryTestTags.DETAIL_EMPTY)).assertCountEquals(0)
    }

    private fun stored(
        number: Int,
        timestamp: String,
        club: String = "driver",
    ) = HistoryShot(
        id = number.toLong(),
        sessionId = "s1",
        eventId = null,
        detail =
            ShotDetail(
                timestamp = timestamp,
                shotNumber = number,
                ballSpeedMph = 140.0,
                estimatedCarryYards = 250.0,
                club = club,
            ),
    )
}
