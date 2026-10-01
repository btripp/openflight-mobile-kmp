// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.ShotEvent
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

/** Plan F8a1 device tests: replay order, tap-to-select in the overlay, the view gestures and the tablet pane. */
@RunWith(AndroidJUnit4::class)
class RangeReplayTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val history = FakeShotHistoryRepository()
    private val piSession = FakePiSessionRepository()
    private val shots = FakeShotRepository()

    private fun setRange(
        windowClass: OfWindowClass,
        replaySessionId: String? = null,
    ): DrivingRangeViewModel {
        var created: DrivingRangeViewModel? = null
        composeRule.setContent {
            OfTheme {
                val viewModel =
                    remember {
                        DrivingRangeViewModel(
                            shots,
                            FakeSettingsRepository(),
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
                    replaySessionId = replaySessionId,
                    windowClass = windowClass,
                )
            }
        }
        composeRule.waitForIdle()
        return assertNotNull(created)
    }

    @Test
    fun given_session_when_replay_then_shotsPlayInOrder() {
        history.put("s1", listOf(stored(3, "pw", 120.0), stored(2, "7-iron", 170.0), stored(1, "driver", 250.0)))
        setRange(OfWindowClass.EXPANDED, replaySessionId = "s1")

        composeRule.onNodeWithTag(RangeTestTags.SHOT_LIST).assertIsDisplayed()
        // Oldest first, each landing and dwelling (0.9 s + 1.25 s) before the next flies.
        for (id in listOf("1", "2", "3")) {
            composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { isSelectedShot(id) }
        }
        composeRule.onNodeWithTag(RangeTestTags.POSITION).assert(hasText("3 / 3"))
    }

    @Test
    fun given_overlay_when_tapLanding_then_shotSelected() {
        history.put("s1", listOf(stored(2, "pw", 120.0), stored(1, "driver", 250.0)))
        val viewModel = setRange(OfWindowClass.EXPANDED)
        composeRule.onNodeWithTag(RangeTestTags.HISTORY).performClick()
        composeRule.onNodeWithTag(RangeTestTags.overlaySession("s1")).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.overlayFlights.size == 2 }
        composeRule.waitForIdle()

        val driver =
            viewModel.uiState.value.browse.overlayFlights
                .first { it.club == "driver" }
        val scene = composeRule.onNodeWithTag(RangeTestTags.SCENE).fetchSemanticsNode()
        val projection =
            RangeProjection(RangeCameraRig().fixedPose, scene.size.width.toFloat(), scene.size.height.toFloat())
        val landing =
            RangeProjection.flightToScene(
                driver.trajectory.points
                    .last()
                    .positionMeters,
            )
        val target = projection.project(landing.x, 0.0, landing.z)

        composeRule.onNodeWithTag(RangeTestTags.SCENE).performTouchInput { click(Offset(target.x, target.y)) }

        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { isSelectedShot(driver.shotId) }
        assertEquals(
            "driver",
            viewModel.uiState.value.displayedShot
                ?.club,
        )
    }

    @Test
    fun given_compactWindow_when_tableTapped_then_sheetListsTheSessionAndARowOpensItsShot() {
        history.put("s1", listOf(stored(2, "pw", 120.0), stored(1, "driver", 250.0)))
        history.currentSessionId.value = "s1"
        val viewModel = setRange(OfWindowClass.COMPACT)

        composeRule.onNodeWithTag(RangeTestTags.TABLE_BUTTON).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) {
            viewModel.uiState.value.table
                ?.rows
                ?.size == 2
        }
        composeRule.onNodeWithTag(RangeTestTags.TABLE_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.tableRow("2")).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.TABLE_AVERAGE).assertIsDisplayed()

        // A row opens its shot on the range, paused, and closes the sheet.
        composeRule.onNodeWithTag(RangeTestTags.tableRow("1")).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) {
            viewModel.uiState.value.displayedShot
                ?.club == "driver"
        }
        composeRule.onNodeWithTag(RangeTestTags.TABLE_PANEL).assertDoesNotExist()
        assertEquals("s1", (viewModel.uiState.value.mode as? RangeMode.Replay)?.sessionId)
    }

    @Test
    fun given_expandedWindow_when_tableTapped_then_panelSitsBesideTheSceneUntilDone() {
        history.put("s1", listOf(stored(1, "driver", 250.0)))
        history.currentSessionId.value = "s1"
        val viewModel = setRange(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(RangeTestTags.TABLE_BUTTON).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) {
            viewModel.uiState.value.table
                ?.rows
                ?.size == 1
        }
        composeRule.onNodeWithTag(RangeTestTags.TABLE_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.SCENE).assertIsDisplayed()
        assertEquals(RangeMode.Live, viewModel.uiState.value.mode)

        composeRule.onNodeWithTag(RangeTestTags.TABLE_CLOSE).performClick()
        composeRule.onNodeWithTag(RangeTestTags.TABLE_PANEL).assertDoesNotExist()
    }

    @Test
    fun given_overlay_when_nextAndPrevTapped_then_selectionStepsThroughTheList() {
        history.put("s1", listOf(stored(2, "pw", 120.0), stored(1, "driver", 250.0)))
        val viewModel = setRange(OfWindowClass.EXPANDED)
        composeRule.onNodeWithTag(RangeTestTags.HISTORY).performClick()
        composeRule.onNodeWithTag(RangeTestTags.overlaySession("s1")).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.overlayFlights.size == 2 }

        composeRule.onNodeWithTag(RangeTestTags.POSITION).assert(hasText("– / 2"))
        composeRule.onNodeWithTag(RangeTestTags.PREVIOUS).assertIsNotEnabled()
        // Newest first: Next picks the pitching wedge, then the driver.
        composeRule.onNodeWithTag(RangeTestTags.NEXT).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { isSelectedShot("2") }
        composeRule.onNodeWithTag(RangeTestTags.NEXT).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { isSelectedShot("1") }
        composeRule.onNodeWithTag(RangeTestTags.POSITION).assert(hasText("2 / 2"))
        composeRule.onNodeWithTag(RangeTestTags.NEXT).assertIsNotEnabled()
        assertEquals(
            "driver",
            viewModel.uiState.value.displayedShot
                ?.club,
        )

        composeRule.onNodeWithTag(RangeTestTags.PREVIOUS).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { isSelectedShot("2") }
        assertEquals(
            "pw",
            viewModel.uiState.value.displayedShot
                ?.club,
        )
    }

    @Test
    fun given_range_when_pinchZoom_then_viewZoomsAndDoubleTapResets() {
        val viewModel = setRange(OfWindowClass.COMPACT)
        composeRule.onNodeWithTag(RangeTestTags.SCENE).performTouchInput {
            pinch(
                start0 = center - Offset(60f, 0f),
                end0 = center - Offset(260f, 0f),
                start1 = center + Offset(60f, 0f),
                end1 = center + Offset(260f, 0f),
            )
        }

        composeRule.onNodeWithTag(RangeTestTags.RESET_VIEW).assertIsDisplayed()
        val zoom = viewModel.uiState.value.browse.view.zoom
        assert(zoom > 1.5) { "zoom was $zoom" }

        composeRule.onNodeWithTag(RangeTestTags.SCENE).performTouchInput { doubleClick(center) }
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.view.isIdentity }
        composeRule.onNodeWithTag(RangeTestTags.RESET_VIEW).assertDoesNotExist()
    }

    /** Plan F8a2p: one finger drags the range like a map; the ground under it follows. */
    @Test
    fun given_range_when_oneFingerDragsDown_then_viewPansDownrangeAndResets() {
        val viewModel = setRange(OfWindowClass.COMPACT)
        composeRule.onNodeWithTag(RangeTestTags.SCENE).performTouchInput {
            swipe(start = Offset(centerX, height * 0.55f), end = Offset(centerX, height * 0.75f), durationMillis = 400)
        }

        composeRule.onNodeWithTag(RangeTestTags.RESET_VIEW).assertIsDisplayed()
        val view = viewModel.uiState.value.browse.view
        assert(view.panZ < -1.0) { "panZ was ${view.panZ}" }
        assertEquals(1.0, view.zoom)

        composeRule.onNodeWithTag(RangeTestTags.RESET_VIEW).performClick()
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.view.isIdentity }
    }

    /** Plan F8a2p: one finger dragged sideways slides the range with it (the view moves the other way). */
    @Test
    fun given_range_when_oneFingerDragsRight_then_viewPansSideways() {
        val viewModel = setRange(OfWindowClass.COMPACT)
        composeRule.onNodeWithTag(RangeTestTags.SCENE).performTouchInput {
            swipe(
                start = Offset(width * 0.3f, height * 0.6f),
                end = Offset(width * 0.7f, height * 0.6f),
                durationMillis = 400,
            )
        }

        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.view.panX < -1.0 }
        assertEquals(0.0, viewModel.uiState.value.browse.view.orbitYawDegrees)
    }

    /** Plan F8a2p: zoom is pinch-only on screen; TalkBack zooms through the scene's custom actions. */
    @Test
    fun given_talkBack_when_zoomActions_then_viewZoomsInAndOut() {
        val viewModel = setRange(OfWindowClass.COMPACT)
        val scene = composeRule.onNodeWithTag(RangeTestTags.SCENE)

        customAction(scene, "Zoom in")
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) { viewModel.uiState.value.browse.view.zoom > 1.4 }

        customAction(scene, "Zoom out")
        composeRule.waitUntil(REPLAY_TIMEOUT_MILLIS) {
            kotlin.math.abs(viewModel.uiState.value.browse.view.zoom - 1.0) < 1e-6
        }
    }

    @Test
    fun given_liveShotDuringReplay_when_chipTapped_then_returnsToLive() {
        history.put("s1", listOf(stored(2, "pw", 120.0), stored(1, "driver", 250.0)))
        val viewModel = setRange(OfWindowClass.COMPACT, replaySessionId = "s1")
        composeRule.onNodeWithTag(RangeTestTags.TRANSPORT).assertIsDisplayed()

        val live = liveShot()
        composeRule.runOnIdle {
            shots.history.value = listOf(live)
            shots.latestShot.value = live
        }
        composeRule.onNodeWithTag(RangeTestTags.NEW_LIVE_SHOT).assertIsDisplayed().performClick()

        composeRule.onNodeWithTag(RangeTestTags.TRANSPORT).assertDoesNotExist()
        assertEquals(RangeMode.Live, viewModel.uiState.value.mode)
    }

    @Test
    fun given_compactWindow_when_replaying_then_noSidePane() {
        history.put("s1", listOf(stored(1, "driver", 250.0)))
        setRange(OfWindowClass.COMPACT, replaySessionId = "s1")
        composeRule.onNodeWithTag(RangeTestTags.TRANSPORT).assertIsDisplayed()
        composeRule.onNodeWithTag(RangeTestTags.SHOT_LIST).assertDoesNotExist()
    }

    /** Runs the scene's TalkBack custom action named [label], as TalkBack's actions menu would. */
    private fun customAction(
        node: SemanticsNodeInteraction,
        label: String,
    ) {
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnUiThread { actions.first { it.label == label }.action() }
    }

    private fun isSelectedShot(id: String): Boolean =
        composeRule
            .onAllNodes(hasTestTag(RangeTestTags.shot(id)) and isSelected())
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun stored(
        id: Long,
        club: String,
        carry: Double,
    ): HistoryShot =
        HistoryShot(
            id = id,
            sessionId = "s1",
            eventId = null,
            detail =
                ShotDetail(
                    timestamp = "2026-09-25T10:00:0$id",
                    club = club,
                    ballSpeedMph = if (club == "driver") 150.0 else 100.0,
                    estimatedCarryYards = carry,
                    launchAngleVertical = if (club == "driver") 12.0 else 24.0,
                    spinRpm = if (club == "driver") 2_500.0 else 7_500.0,
                ),
        )

    private fun liveShot(): ShotEvent =
        ShotEvent(
            schemaVersion = 1,
            eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
            timestamp = "2026-09-25T11:00:00",
            club = "driver",
            ballSpeedMph = 151.4,
            estimatedCarryYards = 264.0,
            launchAngleVertical = 12.6,
            spinRpm = 2_380.0,
        )

    private companion object {
        const val REPLAY_TIMEOUT_MILLIS = 10_000L
    }
}
