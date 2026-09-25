// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ConfidenceLevel
import dev.openflight.companion.core.insights.ShotEnrichment
import dev.openflight.companion.core.insights.SpinSource
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.ShotEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The web-UI parity additions to the dashboard (plans R5b/R6c). */
@RunWith(AndroidJUnit4::class)
class DashboardParityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: DashboardUiState) {
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState = state,
                    onEvent = {},
                    onOpenCalibration = {},
                    onOpenRange = {},
                )
            }
        }
    }

    private fun live(
        enrichment: ShotEnrichment? = null,
        units: UnitSystem = UnitSystem.IMPERIAL,
        chips: List<ClubChip> = emptyList(),
    ) = DashboardUiState.Live(
        connection = ConnectionPanelState(state = ConnectionState.Connected),
        latest = shot,
        previous = emptyList(),
        units = units,
        clubChips = chips,
        enrichments = enrichment?.let { mapOf(shot.eventId to it) }.orEmpty(),
    )

    @Test
    fun givenAnEnrichedShot_whenShown_thenConfidenceDotsSpinSourceCarryRangeAndPlayerShow() {
        show(live(enrichment))

        composeRule
            .onNodeWithTag(DashboardUiTags.confidence("Launch"), useUnmergedTree = true)
            .assert(hasContentDescription("high confidence"))
        composeRule
            .onNodeWithTag(DashboardUiTags.confidence("Spin"), useUnmergedTree = true)
            .assert(hasContentDescription("medium confidence"))
        composeRule
            .onAllNodes(hasText("estimated") and hasTestTag(DashboardUiTags.subtext("Spin")), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule
            .onAllNodes(
                hasText("251-277 yds") and hasAnyAncestor(hasTestTag(DashboardUiTags.CARRY)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeRule.onNodeWithTag(DashboardUiTags.PLAYER, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun givenAShotWithoutEnrichment_whenShown_thenNoBadgesShow() {
        show(live())

        composeRule
            .onAllNodes(
                hasTestTag(DashboardUiTags.confidence("Launch")),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        composeRule
            .onAllNodes(
                hasTestTag(DashboardUiTags.confidence("Spin")),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        composeRule.onAllNodes(hasTestTag(DashboardUiTags.PLAYER), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun givenASpinAdjustedCarry_whenShown_thenItReplacesTheEstimateWithASpinAdjustedSubtext() {
        show(live(enrichment.copy(carrySpinAdjustedYards = 270.4)))

        val carry = hasAnyAncestor(hasTestTag(DashboardUiTags.CARRY))
        composeRule.onAllNodes(hasText("270") and carry, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("spin-adjusted") and carry, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("264") and carry, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun givenMetricUnits_whenShown_thenSpeedsAndDistancesAreConverted() {
        show(live(units = UnitSystem.METRIC))

        val latest = hasAnyAncestor(hasTestTag(DashboardTestTags.LATEST_SHOT))
        // 151.4 mph × 1.60934 = 243.65 km/h; 264 yds × 0.9144 = 241.4 m.
        composeRule.onAllNodes(hasText("243.7") and latest, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText(" KM/H") and latest, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("241") and latest, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText(" M") and latest, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun givenClubChips_whenShown_thenEachClubShowsItsCount() {
        show(live(chips = listOf(ClubChip("driver", 3), ClubChip("7-iron", 2))))

        composeRule.onNodeWithTag(DashboardUiTags.CLUB_CHIPS).performScrollTo().assertIsDisplayed()
        val chips = hasAnyAncestor(hasTestTag(DashboardUiTags.CLUB_CHIPS))
        composeRule.onAllNodes(hasText("Driver") and chips, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("7-Iron") and chips, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("3") and chips, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodes(hasText("5 shots") and chips, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun givenANewShot_whenTheFlashTriggerChanges_thenTheGoldFlashPlaysAndClears() {
        var flashes by mutableIntStateOf(0)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState = live(),
                    onEvent = {},
                    onOpenCalibration = {},
                    onOpenRange = {},
                    shotFlashes = flashes,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodes(hasTestTag(DashboardUiTags.SHOT_FLASH)).assertCountEquals(0)

        flashes = 1
        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS * 3)
        composeRule.onAllNodes(hasTestTag(DashboardUiTags.SHOT_FLASH)).assertCountEquals(1)

        composeRule.mainClock.advanceTimeBy(FLASH_DONE_MILLIS)
        composeRule.onAllNodes(hasTestTag(DashboardUiTags.SHOT_FLASH)).assertCountEquals(0)
    }

    @Test
    fun whenShown_thenRangeAndCalibrateStayOnTheDashboard() {
        // Plan F1a: Session, Training, Camera and Settings moved to the app shell's bottom bar or
        // rail (androidApp's AppNavigationFlowTest); Range and Calibrate stay where they were.
        var openedRange = false
        var openedCalibration = false
        composeRule.setContent {
            OfTheme {
                DashboardScreen(
                    uiState = DashboardUiState.Waiting(ConnectionPanelState()),
                    onEvent = {},
                    onOpenCalibration = { openedCalibration = true },
                    onOpenRange = { openedRange = true },
                )
            }
        }

        composeRule.onNodeWithTag(DashboardTestTags.RANGE).assertIsDisplayed().performClick()
        composeRule
            .onNodeWithTag(
                DashboardTestTags.CALIBRATE_RADAR,
            ).performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(true, openedRange)
        assertEquals(true, openedCalibration)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val FLASH_DONE_MILLIS = 700L

        val shot =
            ShotEvent(
                schemaVersion = 1,
                eventId = "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
                timestamp = "2026-07-29T19:42:10.123456",
                club = "driver",
                ballSpeedMph = 151.4,
                clubSpeedMph = 103.2,
                smashFactor = 1.47,
                estimatedCarryYards = 264.0,
                launchAngleVertical = 12.6,
                launchAngleHorizontal = -1.3,
                spinRpm = 2380.0,
                clubPathDeg = 2.1,
                spinAxisDeg = -3.4,
            )
        val enrichment =
            ShotEnrichment(
                launchAngleConfidence = ConfidenceLevel.HIGH,
                angleSource = "radar",
                spinQuality = ConfidenceLevel.MEDIUM,
                spinSource = SpinSource.ESTIMATED,
                carryRangeLowYards = 251.0,
                carryRangeHighYards = 277.0,
                carrySpinAdjustedYards = null,
                profileName = "Alex",
            )
    }
}
