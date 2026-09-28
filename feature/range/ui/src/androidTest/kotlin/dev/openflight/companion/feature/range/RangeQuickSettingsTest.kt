// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.ViewingProfile
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plan F8f device tests: the gear opens the range quick settings (a sheet on a phone, a side panel on
 * a tablet); changing the trail and the show mode updates the scene without leaving the range and
 * persists through the settings keys Settings › Practice uses.
 */
@RunWith(AndroidJUnit4::class)
class RangeQuickSettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val settings = FakeSettingsRepository()
    private val history = FakeShotHistoryRepository()
    private val shots = FakeShotRepository()
    private val piSession = FakePiSessionRepository()

    private fun setRange(windowClass: OfWindowClass): DrivingRangeViewModel {
        var created: DrivingRangeViewModel? = null
        composeRule.setContent {
            OfTheme {
                val viewModel =
                    remember {
                        DrivingRangeViewModel(
                            shots,
                            settings,
                            history,
                            FakeConditionsRepository(),
                            piSession,
                        )
                    }
                created = viewModel
                DrivingRangeRoute(
                    onExit = {},
                    viewModel = viewModel,
                    reduceMotion = true,
                    windowClass = windowClass,
                    freezeProgress = 1.0,
                )
            }
        }
        composeRule.waitForIdle()
        return assertNotNull(created)
    }

    /** The current session "now": six stored shots, newest first. */
    private fun seedSession() {
        history.put("now", (6L downTo 1L).map { stored(it) })
        history.currentSessionId.value = "now"
    }

    @Test
    fun given_phone_when_gearTapped_then_sheetOpens_and_swipeDownDismissesIt() {
        setRange(OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.quickShow(RangeShowSetting.LIVE)).assertIsSelected()
        composeRule.onNodeWithTag(RangeTestTags.SCENE).assertIsDisplayed()

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS_PANEL).performTouchInput { swipeDown() }
        composeRule.waitUntil(TIMEOUT_MILLIS) { !isShown(RangeTestTags.QUICK_SETTINGS_PANEL) }
        composeRule.onNodeWithTag(RangeTestTags.SCENE).assertIsDisplayed()
    }

    @Test
    fun given_landedShot_when_trailChangedInTheSheet_then_sceneDrawsItAndItPersists() {
        val viewModel = setRange(OfWindowClass.COMPACT)
        shots.latestShot.value = liveShot()
        composeRule.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.activeFlight != null }
        composeRule.waitForIdle()
        assertEquals(0, scene().pinkPixels(), "classic has no neon pink")

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.quickTrail(ShotTrailStyle.NEON)).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.camera.trail.style == ShotTrailStyle.NEON }
        composeRule.onNodeWithTag(RangeTestTags.quickTrail(ShotTrailStyle.NEON)).assertIsSelected()
        composeRule.waitForIdle()

        assertTrue(scene().pinkPixels() > MIN_PIXELS, "the scene draws the neon trail while the sheet is open")
        assertEquals(ShotTrailStyle.NEON, settings.shotTrail.value)
    }

    @Test
    fun given_session_when_showModeChangedInTheSheet_then_sceneOverlaysTheNewestShots() {
        seedSession()
        val viewModel = setRange(OfWindowClass.COMPACT)
        val live = scene()

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.quickShow(RangeShowSetting.LAST_5)).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.browse.overlayFlights.size == 5 }
        composeRule.onNodeWithTag(RangeTestTags.quickShow(RangeShowSetting.LAST_5)).assertIsSelected()
        composeRule.waitForIdle()

        assertEquals(
            listOf("6", "5", "4", "3", "2"),
            viewModel.uiState.value.browse.overlayFlights
                .map { it.shotId },
        )
        assertTrue(scene().differsFrom(live) > MIN_PIXELS, "the five trajectories are drawn")
        assertEquals(RangeShowSetting.LAST_5, settings.rangeShow.value)
        // The club filter appears once something is overlaid.
        composeRule.onNodeWithTag(RangeTestTags.quickClub(null)).performScrollTo().assertIsSelected()
    }

    @Test
    fun given_numbers_when_unitsAndTotalChanged_then_metricsFollow() {
        // Shown on opening (not flown): 120 mph.
        shots.latestShot.value = liveShot()
        setRange(OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.quickUnits(UnitSystem.METRIC)).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { settings.units.value == UnitSystem.METRIC }
        composeRule
            .onNode(hasAnyAncestor(hasTestTag(RangeTestTags.QUICK_SHOW_TOTAL)) and isToggleable())
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { !settings.showTotalDistance.value }

        composeRule.onNodeWithTag(RangeTestTags.BALL_SPEED, useUnmergedTree = true).assertIsDisplayed()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(
                    hasText("KM/H", substring = true) and hasAnyAncestor(hasTestTag(RangeTestTags.BALL_SPEED)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        // 120 mph is 193.1 km/h.
        composeRule
            .onNode(hasText("193.1") and hasAnyAncestor(hasTestTag(RangeTestTags.BALL_SPEED)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun given_tablet_when_gearTapped_then_sidePanelSitsBesideTheScene() {
        setRange(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        val panel = composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS_PANEL).assertIsDisplayed()
        val scene = composeRule.onNodeWithTag(RangeTestTags.SCENE).assertIsDisplayed()
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        val sceneBounds = scene.fetchSemanticsNode().boundsInRoot
        assertTrue(panelBounds.left >= sceneBounds.right - 1f, "the panel sits beside the scene, not over it")

        composeRule.onNodeWithTag(RangeTestTags.quickTrail(ShotTrailStyle.RAINBOW)).performScrollTo().performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { settings.shotTrail.value == ShotTrailStyle.RAINBOW }

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS_CLOSE).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { !isShown(RangeTestTags.QUICK_SETTINGS_PANEL) }
    }

    @Test
    fun given_twoProfilesOnOnePi_when_viewingProfileChanged_then_theOverlayShowsOnlyThoseShots() {
        seedSession()
        piSession.profiles.value =
            ProfilesState(listOf(Profile(ANN, "Ann"), Profile(BO, "Bo")), activeProfileId = ANN, loaded = true)
        val viewModel = setRange(OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.quickViewingProfile(ViewingProfile.FollowActive)).assertIsSelected()
        composeRule.onNodeWithTag(RangeTestTags.quickShow(RangeShowSetting.THIS_SESSION)).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { overlayIds(viewModel) == listOf("5", "3", "1") }

        composeRule.onNodeWithTag(RangeTestTags.quickViewingProfile(ViewingProfile.Pinned(BO))).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { overlayIds(viewModel) == listOf("6", "4", "2") }
        composeRule.onNodeWithTag(RangeTestTags.quickViewingProfile(ViewingProfile.Pinned(BO))).assertIsSelected()
        assertEquals(ViewingProfile.Pinned(BO), settings.viewingProfile.value)

        composeRule.onNodeWithTag(RangeTestTags.quickViewingProfile(ViewingProfile.AllProfiles)).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { overlayIds(viewModel).size == 6 }
        // This device's choice only: the Pi's active profile (shared with other phones) never changes.
        assertTrue(piSession.commands.none { it.startsWith("set_active_profile") })
    }

    @Test
    fun given_noProfiles_when_sheetOpens_then_theViewingProfileControlIsHidden() {
        setRange(OfWindowClass.COMPACT)
        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS).performClick()
        composeRule.onNodeWithTag(RangeTestTags.QUICK_SETTINGS_PANEL).assertIsDisplayed()
        assertTrue(!isShown(RangeTestTags.quickViewingProfile(ViewingProfile.FollowActive)))
    }

    private fun overlayIds(viewModel: DrivingRangeViewModel): List<String> =
        viewModel.uiState.value.browse.overlayFlights
            .map { it.shotId }

    private fun isShown(tag: String): Boolean =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun scene(): ImageBitmap = composeRule.onNodeWithTag(RangeTestTags.SCENE).captureToImage()

    /** The neon tube's hot pink (1, 0.16, 0.78), not in any theme's scene. */
    private fun ImageBitmap.pinkPixels(): Int {
        val pixels = toPixelMap()
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = pixels[x, y]
                if (c.red > 0.8f && c.green < 0.4f && c.blue > 0.55f) count++
            }
        }
        return count
    }

    private fun ImageBitmap.differsFrom(other: ImageBitmap): Int {
        val mine = toPixelMap()
        val theirs = other.toPixelMap()
        var count = 0
        for (y in 0 until minOf(height, other.height)) {
            for (x in 0 until minOf(width, other.width)) if (mine[x, y] != theirs[x, y]) count++
        }
        return count
    }

    private fun stored(id: Long): HistoryShot =
        HistoryShot(
            id = id,
            sessionId = "now",
            eventId = null,
            detail =
                ShotDetail(
                    timestamp = "2026-09-27T10:00:0$id",
                    club = if (id % 2 == 0L) "driver" else "7-iron",
                    ballSpeedMph = if (id % 2 == 0L) 150.0 else 120.0,
                    estimatedCarryYards = if (id % 2 == 0L) 250.0 else 165.0,
                    launchAngleVertical = if (id % 2 == 0L) 12.0 else 16.0,
                    launchAngleHorizontal = (id - 3) * 2.0,
                    spinRpm = if (id % 2 == 0L) 2_500.0 else 7_000.0,
                    // Plan F8f: two people on one Pi, Ann's odd shots and Bo's even ones.
                    profileId = if (id % 2 == 0L) BO else ANN,
                ),
        )

    private fun liveShot(): ShotEvent =
        ShotEvent(
            schemaVersion = 1,
            eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C1F8F0",
            timestamp = "2026-09-27T11:00:00",
            club = "7-iron",
            ballSpeedMph = 120.0,
            estimatedCarryYards = 165.0,
            launchAngleVertical = 16.3,
            spinRpm = 7_000.0,
        )

    private companion object {
        const val MIN_PIXELS = 40
        const val TIMEOUT_MILLIS = 10_000L
        const val ANN = "ann"
        const val BO = "bo"
    }
}
