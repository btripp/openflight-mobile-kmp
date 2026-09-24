// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.feature.dashboard.DashboardTestTags
import dev.openflight.companion.feature.range.RangeTestTags
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
 * Ported from ios/OpenFlightUITests/DrivingRangeUITests.swift: launch with the preview-shot hook,
 * enter the range from the dashboard, check the metrics and the club selector, and exit back.
 *
 * The app graph is the real one ([initKoin] + [applyLaunchOptions]), with the settings kept in
 * memory so no DataStore file outlives a test. They start on Wi-Fi, and the app's Wi-Fi LAN
 * permission (`ACCESS_LOCAL_NETWORK`, API 37+) is granted up front: the dashboard's
 * [TransportPermissionRequest] would otherwise put a system prompt over the app.
 */
@RunWith(AndroidJUnit4::class)
class DrivingRangeFlowTest {
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

    @Suppress("DEPRECATION") // KoinContext: see below.
    private fun launch(options: LaunchOptions) {
        // OpenFlightApplication already started the real graph in this process; replace it.
        stopKoin()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val koin =
            initKoin(extraModules = listOf(module { single<SettingsRepository> { InMemorySettingsRepository() } })) {
                androidContext(context)
            }
        runBlocking { koin.applyLaunchOptions(options) }
        // koin-compose caches the first Koin it sees for the whole process, so without this the
        // composables would keep resolving from an earlier test's (stopped) graph and options.
        // KoinContext is deprecated as "not needed with startKoin", which is untrue for a graph
        // restarted inside one process, like here.
        composeRule.setContent { KoinContext(koin) { OpenFlightApp() } }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun givenThePreviewShot_whenEnteringTheRangeAndExiting_thenMetricsShowAndTheDashboardReturns() {
        launch(LaunchOptions(uiTesting = true, previewShot = true))

        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed().performClick()

        composeRule.onNodeWithTag(RangeTestTags.EXIT).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.BALL_SPEED).assertExists()
        composeRule.onNodeWithTag(RangeTestTags.CARRY).assertExists()
        composeRule.onNodeWithTag(RangeTestTags.CLUB_SELECTOR).assertExists()

        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun givenRangeModeAndPreviewFlight_whenLaunched_thenTheRangeFliesAndExitReturnsToTheDashboard() {
        launch(LaunchOptions(previewShot = true, rangeMode = true, previewFlight = true))

        composeRule.onNodeWithTag(RangeTestTags.EXIT).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.SCENE).assertExists()

        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    private companion object {
        const val API_LOCAL_NETWORK_PERMISSION = 37
    }
}
