// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import dev.openflight.companion.feature.bag.BagTestTags
import dev.openflight.companion.feature.camera.CameraTestTags
import dev.openflight.companion.feature.dashboard.DashboardTestTags
import dev.openflight.companion.feature.dashboard.DashboardUiTags
import dev.openflight.companion.feature.range.RangeTestTags
import dev.openflight.companion.feature.session.SessionHistoryTestTags
import dev.openflight.companion.feature.session.SessionTestTags
import dev.openflight.companion.feature.settings.SettingsTestTags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertTrue

/**
 * Plan F14: the whole app in Demo mode, with no Pi anywhere: "Try without a Pi" connects the pretend
 * Pi and shows the Demo badge; "Hit a shot" reports a made-up shot through the live path (labelled
 * "Demo", spoken by the call-outs, flown by View on range); Sessions and Bag show demo data; the
 * profile switch files the next shot under the new profile; the camera says it needs hardware;
 * and turning Demo mode off in Settings hides every bit of it.
 *
 * The real app graph (Koin's real repositories and Room database), with in-memory settings on Wi-Fi
 * at an address nothing answers, and a fake speech engine.
 */
@RunWith(AndroidJUnit4::class)
class DemoModeFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var speech: FakeSpeechEngine

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
    private fun launch() {
        stopKoin()
        speech = FakeSpeechEngine()
        val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = NOBODY_HOME)
        settings.calloutsEnabled.value = true
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val koin =
            initKoin(
                extraModules =
                    listOf(
                        module {
                            single<SettingsRepository> { settings }
                            single<ConditionsRepository> { FakeConditionsRepository() }
                            single<SpeechEngine> { speech }
                        },
                    ),
            ) {
                androidContext(context)
            }
        composeRule.setContent { KoinContext(koin) { OpenFlightApp(windowClass = OfWindowClass.COMPACT) } }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) = waitFor(hasTestTag(tag))

    private fun waitGone(tag: String) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** "Try without a Pi", then wait for the pretend Pi to connect. */
    private fun enterDemo() {
        composeRule.onNodeWithTag(DashboardTestTags.TRY_DEMO).performScrollTo().performClick()
        waitForTag(AppChromeTags.DEMO_BADGE)
        waitForTag(DashboardTestTags.HIT_SHOT)
        // Connected: the club menu is enabled once the pretend Pi is up.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(hasTestTag(DashboardTestTags.CLUB_SELECTOR) and enabled(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun enabled() = SemanticsMatcher("is enabled") { SemanticsProperties.Disabled !in it.config }

    private fun hitAShot() {
        composeRule.onNodeWithTag(DashboardTestTags.HIT_SHOT).performScrollTo().performClick()
        waitForTag(DashboardTestTags.DEMO_SHOT_TAG)
    }

    @Test
    fun given_noPi_when_tryWithoutAPi_then_theDemoPiConnectsUnderTheDemoBadge_and_exitReturnsToTheRealFlow() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)

        enterDemo()
        composeRule.onNodeWithTag(AppChromeTags.DEMO_BADGE).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTestTags.EXIT_DEMO).assertIsDisplayed()

        composeRule.onNodeWithTag(DashboardTestTags.EXIT_DEMO).performClick()
        waitGone(AppChromeTags.DEMO_BADGE)
        waitForTag(DashboardTestTags.TRY_DEMO)
        composeRule.onNodeWithTag(DashboardTestTags.HOST_FIELD).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun given_demoMode_when_hitAShot_then_itIsLabelledDemo_spoken_and_viewableOnTheRange() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)
        enterDemo()

        hitAShot()

        // The call-out speaks the shot once it's final (provisional first, then the shot_update).
        composeRule.waitUntil(TIMEOUT_MILLIS) { speech.spoken.isNotEmpty() }
        assertTrue(
            speech.spoken
                .single()
                .text
                .contains("yards"),
            "spoke ${speech.spoken}",
        )

        composeRule.onNodeWithTag(DashboardTestTags.VIEW_ON_RANGE).performScrollTo().performClick()
        waitForTag(RangeTestTags.EXIT)
        waitForTag(RangeTestTags.CARRY)
        composeRule.onNodeWithTag(AppChromeTags.DEMO_BADGE).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()
        waitForTag(DashboardTestTags.HIT_SHOT)
    }

    @Test
    fun given_demoMode_when_switchingProfile_then_theNextShotIsFiledUnderIt() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)
        enterDemo()

        composeRule.onNodeWithTag(DashboardTestTags.PROFILE_BUTTON).performScrollTo().performClick()
        waitForTag(DashboardTestTags.profileRow("preview-bob"))
        composeRule.onNodeWithTag(DashboardTestTags.profileRow("preview-bob")).performClick()
        waitGone(DashboardTestTags.PROFILE_SHEET)

        hitAShot()
        waitFor(hasTestTag(DashboardUiTags.PLAYER) and hasText("Bob"))
    }

    @Test
    fun given_demoMode_when_openingSessionsAndBag_then_demoDataShows() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)
        enterDemo()
        hitAShot()

        composeRule.onNodeWithTag(AppNavTags.SESSIONS).performClick()
        waitFor(hasTestTag(SessionTestTags.stat("Shots")) and hasContentDescriptionContaining("Shots, 1"))
        composeRule.onNodeWithTag(SessionHistoryTestTags.OPEN).performClick()
        // The live demo session plus the seeded past ones, each from the "Demo Pi".
        waitFor(hasText("Demo Pi", substring = true))
        composeRule.onNodeWithTag(SessionHistoryTestTags.DONE).performClick()

        composeRule.onNodeWithTag(AppNavTags.BAG).performClick()
        // The driver heads the bag, so its row is on screen without scrolling the list.
        waitForTag(BagTestTags.club("driver"))
        // The row's one screen-reader stop sits inside the tagged row.
        waitFor(
            hasAnyAncestor(hasTestTag(BagTestTags.club("driver"))) and hasContentDescriptionContaining("average carry"),
        )
    }

    /**
     * The camera needs hardware too. (Calibration's "needs a real Pi" is covered by
     * `CalibrationScreenTest`: its live gravity sampling keeps an app-level test from idling.)
     */
    @Test
    fun given_demoMode_when_openingTheCamera_then_itShowsThePlaceholder() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)
        enterDemo()

        composeRule.onNodeWithTag(AppNavTags.SETTINGS).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.OPEN_CAMERA).performScrollTo().performClick()
        waitForTag(CameraTestTags.DEMO_PLACEHOLDER)
    }

    @Test
    fun given_demoData_when_demoModeIsTurnedOffInSettings_then_itIsHidden() {
        launch()
        waitForTag(DashboardTestTags.TRY_DEMO)
        enterDemo()
        hitAShot()

        composeRule.onNodeWithTag(AppNavTags.SETTINGS).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DEMO_SWITCH).performScrollTo()
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag(SettingsTestTags.DEMO_SWITCH))).performClick()
        waitGone(AppChromeTags.DEMO_BADGE)

        composeRule.onNodeWithTag(AppNavTags.PRACTICE).performClick()
        waitForTag(DashboardTestTags.TRY_DEMO)
        waitGone(DashboardTestTags.DEMO_SHOT_TAG)

        composeRule.onNodeWithTag(AppNavTags.SESSIONS).performClick()
        composeRule.onNodeWithTag(SessionHistoryTestTags.OPEN).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(hasText("Demo Pi", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun hasContentDescriptionContaining(text: String) =
        SemanticsMatcher("content description contains \"$text\"") { node ->
            node.config
                .getOrElseNullable(
                    SemanticsProperties.ContentDescription,
                ) { null }
                .orEmpty()
                .any { it.contains(text) }
        }

    private companion object {
        const val API_LOCAL_NETWORK_PERMISSION = 37
        const val TIMEOUT_MILLIS = 15_000L

        /** A Wi-Fi host nothing answers on, so the real Pi never connects. */
        const val NOBODY_HOME = "127.0.0.1:9"
    }
}
