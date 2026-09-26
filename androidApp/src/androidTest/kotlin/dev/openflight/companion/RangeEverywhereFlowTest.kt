// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.feature.range.RangeTestTags
import dev.openflight.companion.feature.session.SessionHistoryTestTags
import dev.openflight.companion.feature.session.SessionTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Plan F8d: "View on range" from a stored session's shot row opens the range paused on that shot,
 * with next/previous/play available. The real app graph, phone layout (the docked tablet
 * pane is covered by `SessionHistoryTabletLayoutTest`), with the `--preview-history` sessions.
 */
@RunWith(AndroidJUnit4::class)
class RangeEverywhereFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= API_LOCAL_NETWORK_PERMISSION) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                "android.permission.ACCESS_LOCAL_NETWORK",
            )
        }
    }

    @Suppress("DEPRECATION") // KoinContext: see AppNavigationFlowTest.
    private fun launch(windowClass: OfWindowClass) {
        stopKoin()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val koin =
            initKoin(
                extraModules =
                    listOf(
                        module {
                            single<SettingsRepository> { InMemorySettingsRepository() }
                            single<ConditionsRepository> { FakeConditionsRepository() }
                        },
                    ),
            ) {
                androidContext(context)
            }
        runBlocking { koin.applyLaunchOptions(LaunchOptions(uiTesting = true, previewHistory = true)) }
        composeRule.setContent { KoinContext(koin) { OpenFlightApp(windowClass = windowClass) } }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun given_historyRow_when_viewOnRange_then_rangeOpensOnThatShot() {
        launch(OfWindowClass.COMPACT)
        openCurrentSession()

        rowRangeButton().performClick()

        assertRangePausedOnTheSecondShot()
    }

    private fun openCurrentSession() {
        composeRule.onNodeWithTag(AppNavTags.SESSIONS).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.OPEN).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.session(CURRENT_SESSION)).performClick()
    }

    private fun rowRangeButton(): SemanticsNodeInteraction {
        val scrollable = hasScrollToNodeAction()
        val tag = SessionTestTags.viewOnRange(SECOND_SHOT_TIMESTAMP)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(scrollable).performScrollToNode(hasTestTag(tag))
        return composeRule.onNodeWithTag(tag)
    }

    private fun assertRangePausedOnTheSecondShot() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(
                    hasTestTag(RangeTestTags.POSITION) and hasText("2 / 3"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(RangeTestTags.EXIT).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.PLAY_PAUSE).assert(hasText("Play"))
    }

    private companion object {
        const val API_LOCAL_NETWORK_PERMISSION = 37
        const val TIMEOUT_MILLIS = 10_000L

        /** `PreviewShotHistoryRepository`'s current session and its shot #2 (a 7-iron). */
        const val CURRENT_SESSION = "preview-current"
        const val SECOND_SHOT_TIMESTAMP = "2026-09-25T10:21:40.500000"
    }
}
