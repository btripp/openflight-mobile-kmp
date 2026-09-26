// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.designsystem.OfAdaptiveScaffoldTags
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.feature.bag.BagTestTags
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
import kotlin.test.assertTrue

/**
 * Plans R5b/R6c: the app's navigation reaches every screen, and the CSV export becomes a `text/csv`
 * share intent. Plan F1a: the bar lives in the app shell and becomes a navigation rail on tablets
 * (the window class is forced, not the device's). Plan F1d: the top-level destinations are
 * Practice · Sessions · Bag · Settings (tapping Practice, not a per-screen "Done", returns to the
 * dashboard), while Training (Practice's overflow) and Camera (Settings' Device group) are pushed,
 * hide the bar and come back with their own Done. Same real app graph and in-memory settings as
 * [DrivingRangeFlowTest].
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
        fontScale: Float = 1f,
    ) {
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
        runBlocking { koin.applyLaunchOptions(options) }
        // koin-compose caches the first Koin it sees for the whole process, so without this the
        // composables would keep resolving from an earlier test's (stopped) graph and options.
        // KoinContext is deprecated as "not needed with startKoin", which is untrue for a graph
        // restarted inside one process, like here.
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KoinContext(koin) { OpenFlightApp(windowClass = windowClass) }
            }
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Plans F1b/F1d: Sessions, Bag and Settings are top-level destinations, so the bar/rail stays on
     * screen there too and they have no "Done" button: tapping Practice returns to the dashboard.
     */
    private fun openAndReturn(
        entry: String,
        arrived: String,
        check: () -> Unit,
    ) {
        composeRule.onNodeWithTag(entry).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(arrived).assertIsDisplayed()
        check()
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun givenThePreviewShot_whenEachBottomBarScreenIsOpenedAndClosed_thenTheDashboardReturns() {
        launch(LaunchOptions(uiTesting = true, previewShot = true))

        openAndReturn(AppNavTags.SESSIONS, SessionTestTags.SOURCE) {
            // The preview shot is the phone's only shot: no Pi link, so the local source.
            composeRule.onNodeWithTag(SessionTestTags.stat("Shots")).assert(hasContentDescription("Shots, 1"))
        }
        openAndReturn(AppNavTags.BAG, BagTestTags.CONDITIONS_CARD) {
            composeRule.onNodeWithTag(BagTestTags.CONDITIONS_CARD).assertIsDisplayed()
        }
        openAndReturn(AppNavTags.SETTINGS, SettingsTestTags.GROUP_DEVICE) {
            composeRule.onNodeWithTag(SettingsTestTags.CONNECTION).assertIsDisplayed()
            composeRule.onNodeWithTag(SettingsTestTags.UNITS).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * Plan F1d: the same top-level entries, labels and order as iOS's `AppTab`. Today that's four;
     * F9b/F9c insert Play at index 1 and add it here.
     */
    @Test
    fun given_compact_when_launch_then_topLevelEntriesInOrder() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.COMPACT)

        assertEquals(
            listOf("Practice", "Sessions", "Bag", "Settings"),
            TopLevelDestination.entries.map { it.label },
        )
        val lefts =
            navEntries.map { (tag, label) ->
                composeRule
                    .onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().left
            }
        assertEquals(lefts.sorted(), lefts)
        assertEquals(lefts.size, lefts.distinct().size)
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).assertIsSelected()
    }

    /** Plan F1d: Speed training is pushed from Practice's overflow, hides the bar and Done returns. */
    @Test
    fun given_practice_when_openTraining_then_doneReturns() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(DashboardTestTags.MORE).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.OPEN_TRAINING).performClick()
        composeRule.onNodeWithTag(TrainingTestTags.MODE).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertCountEquals(0)

        composeRule.onNodeWithTag(TrainingTestTags.DONE).performClick()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).assertIsSelected()
    }

    /** Plan F1d: Camera is pushed from Settings' Device group, hides the bar and Done returns. */
    @Test
    fun given_settings_when_tapCamera_then_cameraPushedAndBarHidden() {
        launch(LaunchOptions(uiTesting = true, previewShot = true), OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(AppNavTags.SETTINGS).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.OPEN_CAMERA).performScrollTo().performClick()
        composeRule.onNodeWithTag(CameraTestTags.FEED).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertCountEquals(0)

        composeRule.onNodeWithTag(CameraTestTags.DONE).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.OPEN_CAMERA).assertExists()
        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(AppNavTags.SETTINGS).assertIsSelected()
    }

    /**
     * R8f leftover, fixed in plan F1b: at a 200 % system font scale a label like "Settings" no
     * longer fit the bar/rail's fixed-width cell on one line, and without a break opportunity
     * Compose wrapped it mid-word ("Setti"/"ngs"). `OfAdaptiveScaffold` now caps every label to one
     * line with an ellipsis instead (`OfAdaptiveScaffold.kt`).
     */
    private fun assertNoNavLabelWraps(vararg entries: Pair<String, String>) {
        for ((tag, label) in entries) {
            val nodes =
                composeRule
                    .onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty(), "\"$label\" isn't shown under \"$tag\"")
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                for (layout in layouts) {
                    assertEquals(1, layout.lineCount, "\"$label\" wrapped at 200 % text")
                }
            }
        }
    }

    private val navEntries =
        arrayOf(
            AppNavTags.PRACTICE to "Practice",
            AppNavTags.SESSIONS to "Sessions",
            AppNavTags.BAG to "Bag",
            AppNavTags.SETTINGS to "Settings",
        )

    @Test
    fun given200PercentText_whenCompact_thenNoBottomBarLabelWraps() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.COMPACT, fontScale = 2f)

        assertNoNavLabelWraps(*navEntries)
    }

    @Test
    fun given200PercentText_whenExpanded_thenNoNavigationRailLabelWraps() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.EXPANDED, fontScale = 2f)

        assertNoNavLabelWraps(*navEntries)
    }

    @Test
    fun given_compactWidth_when_launch_then_bottomBarShown() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.RAIL).assertCountEquals(0)
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).assertIsSelected()
    }

    @Test
    fun given_expandedWidth_when_launch_then_navigationRailShown() {
        launch(LaunchOptions(uiTesting = true), OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(OfAdaptiveScaffoldTags.RAIL).assertIsDisplayed()
        composeRule.onAllNodesWithTag(OfAdaptiveScaffoldTags.BOTTOM_BAR).assertCountEquals(0)
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).assertIsSelected()
        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed()
    }

    @Test
    fun given_expandedWidth_when_eachRailEntryTapped_then_itsScreenOpensAndIsSelected() {
        launch(LaunchOptions(uiTesting = true, previewShot = true), OfWindowClass.EXPANDED)

        for ((entry, screen) in listOf(
            AppNavTags.SESSIONS to SessionTestTags.SOURCE,
            AppNavTags.BAG to BagTestTags.CONDITIONS_CARD,
            AppNavTags.SETTINGS to SettingsTestTags.CONNECTION,
        )) {
            composeRule.onNodeWithTag(entry).performClick()
            composeRule.onNodeWithTag(screen).assertIsDisplayed()
            composeRule.onNodeWithTag(entry).assertIsSelected()
        }
        composeRule.onNodeWithTag(AppNavTags.PRACTICE).performClick()
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
