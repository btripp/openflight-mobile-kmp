// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ClubStats
import dev.openflight.companion.core.insights.DispersionArc
import dev.openflight.companion.core.insights.DispersionViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan R8f accessibility pass on the Session screen: the chart for TalkBack, and 200 % text. */
@RunWith(AndroidJUnit4::class)
class SessionAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<SessionEvent>()

    private fun show(
        state: SessionUiState,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OfTheme { SessionScreen(uiState = state, onEvent = { events += it }, onBack = {}) }
            }
        }
    }

    private val withChart =
        SessionUiState(
            source = SessionSource.LOCAL,
            allCount = 2,
            clubChips = listOf(ClubChip("7-iron", 1), ClubChip("driver", 1)),
            stats =
                ClubStats(
                    shotCount = 2,
                    avgBallSpeedMph = 134.8,
                    maxBallSpeedMph = 151.4,
                    avgCarryYards = 214.6,
                    avgClubSpeedMph = null,
                    avgSmashFactor = null,
                    minBallSpeedMph = 118.2,
                    stdDevBallSpeedMph = 23.3,
                ),
            shots = previewRows,
            dispersion =
                SessionDispersionUiState(
                    points =
                        listOf(
                            DispersionPoint(previewRows[0].id, 2, "7-iron", "7i", 1, 165.0, -3.0, sideEstimated = true),
                            DispersionPoint(previewRows[1].id, 1, "driver", "D", 0, 264.0, 6.0, sideEstimated = false),
                        ),
                    ellipses = emptyList(),
                    viewport =
                        DispersionViewport(
                            minCarryYards = 150.0,
                            maxCarryYards = 280.0,
                            halfWidthYards = 20.0,
                            arcs = listOf(DispersionArc(200.0, 200), DispersionArc(250.0, 250)),
                        ),
                    estimatedSideCount = 1,
                ),
        )

    @Test
    fun givenShotsOnTheChart_whenReadByTalkBack_thenEachClubIsSummarisedAndTheDotsCanBeStepped() {
        show(withChart)
        val chart =
            composeRule.onNode(
                hasContentDescription(
                    "Driver: 1 shot, 264 yds average carry, 6 yds right on average. " +
                        "7-Iron: 1 shot, 165 yds average carry, side not measured.",
                    substring = true,
                ),
            )
        chart.assert(hasStateDescription(DispersionCopy.NO_SELECTION))

        val actions = chart.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(DispersionCopy.NEXT_SHOT, DispersionCopy.PREVIOUS_SHOT), actions.map { it.label })
        composeRule.runOnIdle { actions.first { it.label == DispersionCopy.NEXT_SHOT }.action() }

        // Shot 1 (the driver) is the oldest, so "next" from nothing selected picks it.
        assertEquals(listOf<SessionEvent>(SessionEvent.SelectShot(previewRows[1].id)), events)
    }

    @Test
    fun givenASelectedDot_whenReadByTalkBack_thenItIsTheChartsStateAndCanBeCleared() {
        val selected =
            SelectedShotCard(previewRows[1].id, 1, "Driver", carryYards = 264.0, spinRpm = null, clubSpeedMph = null)
        show(withChart.copy(selectedShot = selected))
        val chart = composeRule.onNode(hasContentDescription("Dispersion chart", substring = true))

        chart.assert(hasStateDescription("Shot 1, Driver, 264 yds carry, 6 yds right"))
        val actions = chart.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { actions.first { it.label == DispersionCopy.CLEAR_SELECTION }.action() }

        assertEquals(listOf<SessionEvent>(SessionEvent.SelectShot(null)), events)
    }

    @Test
    fun given200PercentText_whenShown_thenStatsRowsAndActionsAreNotClipped() {
        val delete = SessionAction.DeleteShot(previewRows.first().id, 2, "7-Iron")
        show(
            withChart.copy(
                action =
                    SessionActionState.Failed(
                        delete,
                        SessionActionCopy.failedTitle(delete),
                        "The connection dropped before the server replied.",
                        canRetry = true,
                    ),
            ),
            fontScale = 2f,
        )

        listOf(
            "Couldn't delete shot #2",
            "The connection dropped before the server replied.",
            "Ball Std Dev (mph)",
            "Avg Carry (yds)",
            "Min Ball (mph)",
            "7-Iron",
        ).forEach(::assertNotClipped)
    }

    /**
     * Scrolls to the text and checks every layout of it shows all its characters (no line cut off
     * or ellipsized, nothing below the last line) and stays within the screen's width.
     */
    private fun assertNotClipped(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        val rootWidth =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .size.width
        val nodes = composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "\"$text\" isn't shown")
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val lastLine = layout.lineCount - 1
            assertFalse(layout.didOverflowHeight, "\"$text\" is cut off below at 200 % text")
            assertFalse(layout.isLineEllipsized(lastLine), "\"$text\" is ellipsized at 200 % text")
            assertEquals(
                layout.layoutInput.text.length,
                layout.getLineEnd(lastLine, visibleEnd = true),
                "\"$text\" doesn't show all its characters at 200 % text",
            )
            assertTrue(node.boundsInRoot.right <= rootWidth + 1, "\"$text\" runs off the screen at 200 % text")
        }
    }
}
