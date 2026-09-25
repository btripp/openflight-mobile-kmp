// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.designsystem.OfAdaptiveScaffoldTags
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.feature.camera.CameraTestTags
import dev.openflight.companion.feature.dashboard.DashboardTestTags
import dev.openflight.companion.feature.range.RangeTestTags
import dev.openflight.companion.feature.session.SessionTestTags
import dev.openflight.companion.feature.settings.SettingsTestTags
import dev.openflight.companion.feature.training.TrainingTestTags
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Plans R5b/R6c: the app's bottom bar reaches Session, Training, Camera and Settings, each "Done"
 * returns to the dashboard, and the CSV export becomes a `text/csv` share intent. Plan F1a: the bar
 * lives in the app shell and becomes a navigation rail on tablets (the window class is forced, not
 * the device's). Same real app graph and in-memory settings as [DrivingRangeFlowTest].
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationFlowTest {
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
    private fun launch(
        options: LaunchOptions,
        windowClass: OfWindowClass = OfWindowClass.COMPACT,
    ) {
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
        composeRule.setContent { KoinContext(koin) { OpenFlightApp(windowClass = windowClass) } }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun openAndReturn(
        entry: String,
        done: String,
        check: () -> Unit,
    ) {
        composeRule.onNodeWithTag(entry).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(done).assertIsDisplayed()
        check()
        composeRule.onNodeWithTag(done).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun givenThePreviewShot_whenEachBottomBarScreenIsOpenedAndClosed_thenTheDashboardReturns() {
        launch(LaunchOptions(uiTesting = true, previewShot = true))

        openAndReturn(AppNavTags.SESSION, SessionTestTags.DONE) {
            // The preview shot is the phone's only shot: no Pi link, so the local source.
            composeRule.onNodeWithTag(SessionTestTags.stat("Shots")).assert(hasContentDescription("Shots, 1"))
        }
        openAndReturn(AppNavTags.TRAINING, TrainingTestTags.DONE) {
            composeRule.onNodeWithTag(TrainingTestTags.AVAILABILITY).assertIsDisplayed()
        }
        openAndReturn(AppNavTags.CAMERA, CameraTestTags.DONE) {
            composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertIsDisplayed()
        }
        openAndReturn(AppNavTags.SETTINGS, SettingsTestTags.DONE) {
            composeRule.onNodeWithTag(SettingsTestTags.UNITS).assertIsDisplayed()
        }
    }

    @Test
    fun given_compactWidth_when_launch_then_bottomBarShown() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.RAIL).assertCountEquals(0)
        composeRule.onNodeWithTag(AppNavTags.HOME).assertIsSelected()
    }

    @Test
    fun given_expandedWidth_when_launch_then_navigationRailShown() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.RAIL).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertCountEquals(0)
        composeRule.onNodeWithTag(AppNavTags.HOME).assertIsSelected()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun given_expandedWidth_when_eachRailEntryTapped_then_itsScreenOpensAndIsSelected() {
        launch(LaunchOptions(uiTesting = true, previewShot = true), OfWindowClass.EXPANDED)

        for ((entry, screen) in listOf(
            AppNavTags.SESSION to SessionTestTags.DONE,
            AppNavTags.TRAINING to TrainingTestTags.DONE,
            AppNavTags.CAMERA to CameraTestTags.DONE,
            AppNavTags.SETTINGS to SettingsTestTags.DONE,
        )) {
            composeRule.onNodeWithTag(entry).performClick()
            composeRule.onNodeWithTag(screen).assertIsDisplayed()
            composeRule.onNodeWithTag(entry).assertIsSelected()
        }
        composeRule.onNodeWithTag(AppNavTags.HOME).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun given_expandedWidth_when_pushedScreenOpened_then_railHiddenUntilBack() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(DashboardTestTags.RANGE).performClick()
        composeRule.onNodeWithTag(RangeTestTags.EXIT).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.RAIL).assertCountEquals(0)

        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()
        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.RAIL).assertIsDisplayed()
    }

    @Test
    fun givenAnExport_whenShared_thenTheShareSheetGetsAReadableTextCsvStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val csv = "shot_number,club\n1,driver\n"

        val file = writeExport(context, csv, "openflight shots:2026.csv")
        val chooser = shareIntent(context, file)

        assertEquals("openflight_shots_2026.csv", file.name)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(CSV_MIME_TYPE, send.type)
        val uri = assertNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val shared = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        assertEquals(csv, shared)
    }

    private companion object {
        const val API_LOCAL_NETWORK_PERMISSION = 37
    }
}
