// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.HistoryShot
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfWindowClass
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tester report (build 5): "Largest text size: layout issues appeared primarily in Range." At 200%
 * text the replay transport (Prev/Next) was squeezed off a narrow scene and the shot table cut its
 * clubs and headers short ("Dri…", "Ball…"). These keep the key controls and numbers on screen.
 */
@RunWith(AndroidJUnit4::class)
class RangeLargeTextTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val history = FakeShotHistoryRepository()

    @After
    fun resetOrientation() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test
    fun given200PercentText_whenReplayingOnAPhone_thenTheTransportAndKeyNumbersAreOnScreen() {
        val viewModel = setRange(OfWindowClass.COMPACT, replaySessionId = SESSION)
        waitForReplay(viewModel)

        for (tag in listOf(
            RangeTestTags.LIVE,
            RangeTestTags.PREVIOUS,
            RangeTestTags.PLAY_PAUSE,
            RangeTestTags.NEXT,
            RangeTestTags.EXIT,
            RangeTestTags.BALL_SPEED,
            RangeTestTags.CARRY,
        )) {
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    /**
     * A phone in landscape can be an expanded window: the scene sits between the replay list and
     * the docked metrics, too narrow for the controls in one row. They used to wrap tall enough to
     * squeeze the transport to nothing.
     */
    @Test
    fun given200PercentTextAndANarrowDockedScene_whenReplaying_thenPrevAndNextStayOnScreen() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        val viewModel = setRange(OfWindowClass.EXPANDED, replaySessionId = SESSION)
        waitForReplay(viewModel)

        composeRule.onNodeWithTag(RangeTestTags.METRICS_DOCK).assertIsDisplayed()
        for (tag in listOf(RangeTestTags.PREVIOUS, RangeTestTags.PLAY_PAUSE, RangeTestTags.NEXT)) {
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    /**
     * The table panel beside the scene (an expanded window) shares its grid with the phone's sheet.
     * The panel, not the sheet, because the sheet is its own window, which doesn't take this
     * test's [LocalDensity] font scale (the system setting does reach it).
     */
    @Test
    fun given200PercentText_whenTheShotTableOpens_thenNoClubOrNumberIsCutShort() {
        history.currentSessionId.value = SESSION
        val viewModel = setRange(OfWindowClass.EXPANDED)

        composeRule.onNodeWithTag(RangeTestTags.TABLE_BUTTON).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            viewModel.uiState.value.table
                ?.rows
                ?.size == 2
        }
        composeRule.onNodeWithTag(RangeTestTags.tableRow("2")).assertIsDisplayed()

        val texts =
            composeRule
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(RangeTestTags.TABLE_PANEL)) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue(texts.any { "Driver" in it.layoutText() }, "no club in the table")
        for (node in texts) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line), "\"$text\" ellipsized")
            }
        }
    }

    private fun setRange(
        windowClass: OfWindowClass,
        replaySessionId: String? = null,
    ): DrivingRangeViewModel {
        history.put(SESSION, listOf(stored(2, "7-iron", 165.0), stored(1, "driver", 264.0)))
        var created: DrivingRangeViewModel? = null
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = FONT_SCALE)) {
                OfTheme {
                    val viewModel =
                        remember {
                            DrivingRangeViewModel(
                                FakeShotRepository(),
                                FakeSettingsRepository(),
                                history,
                                FakeConditionsRepository(),
                                FakePiSessionRepository(),
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
        }
        composeRule.waitForIdle()
        return assertNotNull(created)
    }

    private fun waitForReplay(viewModel: DrivingRangeViewModel) {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            val state = viewModel.uiState.value
            state.mode is RangeMode.Replay && state.browse.shots.size == 2
        }
        composeRule.waitForIdle()
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.layoutText(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts
            .singleOrNull()
            ?.layoutInput
            ?.text
            ?.text
            .orEmpty()
    }

    private fun stored(
        id: Long,
        club: String,
        carry: Double,
    ): HistoryShot =
        HistoryShot(
            id = id,
            sessionId = SESSION,
            eventId = null,
            detail =
                ShotDetail(
                    timestamp = "2026-09-25T10:00:0$id",
                    club = club,
                    ballSpeedMph = if (club == "driver") 151.4 else 118.2,
                    estimatedCarryYards = carry,
                    launchAngleVertical = if (club == "driver") 12.6 else 24.0,
                    spinRpm = if (club == "driver") 2_380.0 else 7_000.0,
                ),
        )

    private companion object {
        const val SESSION = "s1"
        const val FONT_SCALE = 2f
        const val TIMEOUT_MILLIS = 10_000L
    }
}
