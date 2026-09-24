// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.feature.camera.CameraTestTags
import dev.openflight.companion.feature.dashboard.DashboardTestTags
import dev.openflight.companion.feature.dashboard.DashboardUiTags
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
 * Plans R5b/R6c: the dashboard's bottom bar reaches Session, Training, Camera and Settings, each
 * "Done" returns to the dashboard, and the CSV export becomes a `text/csv` share intent. Same real
 * app graph and in-memory settings as [DrivingRangeFlowTest].
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
    private fun launch(options: LaunchOptions) {
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

        openAndReturn(DashboardUiTags.SESSION, SessionTestTags.DONE) {
            // The preview shot is the phone's only shot: no Pi link, so the local source.
            composeRule.onNodeWithTag(SessionTestTags.stat("Shots")).assert(hasContentDescription("Shots, 1"))
        }
        openAndReturn(DashboardUiTags.TRAINING, TrainingTestTags.DONE) {
            composeRule.onNodeWithTag(TrainingTestTags.AVAILABILITY).assertIsDisplayed()
        }
        openAndReturn(DashboardUiTags.CAMERA, CameraTestTags.DONE) {
            composeRule.onNodeWithTag(CameraTestTags.PHASE_TITLE).assertIsDisplayed()
        }
        openAndReturn(DashboardUiTags.SETTINGS, SettingsTestTags.DONE) {
            composeRule.onNodeWithTag(SettingsTestTags.UNITS).assertIsDisplayed()
        }
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
