// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class TrainingScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<TrainingEvent>()

    private fun show(state: TrainingUiState) {
        composeRule.setContent { OfTheme { TrainingScreen(uiState = state, onEvent = { events += it }, onBack = {}) } }
    }

    @Test
    fun givenTheServersImplements_whenShown_thenEveryGroupIsListedInPickerOrder() {
        show(previewTrainingState())

        for (group in TrainingImplements.groups) {
            composeRule.onNodeWithTag(TrainingTestTags.group(group.name)).performScrollTo().assertIsDisplayed()
            for (option in group.options) {
                composeRule.onNodeWithTag(TrainingTestTags.implement(option.id)).assertExists()
            }
        }
        assertEquals(listOf("General", "SuperSpeed", "TheStack", "Rypstick"), TrainingImplements.groups.map { it.name })
    }

    @Test
    fun givenTheLink_whenAnImplementIsTapped_thenItIsSelected() {
        show(previewTrainingState())
        val option =
            TrainingImplements.groups
                .first { it.name == "TheStack" }
                .options
                .first()

        composeRule.onNodeWithTag(TrainingTestTags.implement(TrainingImplements.DEFAULT_ID)).assertIsSelected()
        composeRule
            .onNodeWithTag(
                TrainingTestTags.implement(option.id),
            ).performScrollTo()
            .assertIsNotSelected()
            .performClick()

        assertEquals(listOf<TrainingEvent>(TrainingEvent.SelectImplement(option.id)), events)
    }

    @Test
    fun givenNoLink_whenShown_thenTheImplementsAreDisabledWithTheReason() {
        show(
            previewTrainingState(availability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.REQUIRES_WIFI)),
        )

        composeRule.onNodeWithTag(TrainingTestTags.AVAILABILITY).assertIsDisplayed()
        // Once on the session card, once over the picker.
        composeRule.onAllNodes(hasText(PiFeatureAvailability.REQUIRES_WIFI)).assertCountEquals(2)
        composeRule
            .onNodeWithTag(
                TrainingTestTags.implement(TrainingImplements.DEFAULT_ID),
            ).assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithTag(TrainingTestTags.SIMULATE).assertIsNotEnabled()

        assertEquals(emptyList(), events)
    }

    @Test
    fun givenReps_whenShown_thenLastBestAndAverageShow() {
        show(previewTrainingState())

        composeRule.onNodeWithTag(TrainingTestTags.LAST).assert(hasContentDescription("Last, 104.2 mph"))
        composeRule.onNodeWithTag(TrainingTestTags.BEST).assert(hasContentDescription("Best, 108.9 mph"))
        composeRule.onNodeWithTag(TrainingTestTags.AVERAGE).assert(hasContentDescription("Average, 105.1 mph"))
        composeRule.onNodeWithText("3 swings (this player and implement)").assertIsDisplayed()
    }

    @Test
    fun givenMetricUnitsAndNoReps_whenShown_thenDashesInKilometersPerHour() {
        show(previewTrainingState(stats = SwingSpeedStats.EMPTY).copy(units = UnitSystem.METRIC))

        composeRule.onNodeWithTag(TrainingTestTags.BEST).assert(hasContentDescription("Best, — km/h"))
        composeRule.onNodeWithText("No swings yet").assertIsDisplayed()
    }

    @Test
    fun givenAnError_whenTapped_thenItIsDismissed() {
        show(previewTrainingState(error = "Unknown training implement"))

        composeRule.onNodeWithText("⚠ Unknown training implement").assertIsDisplayed()
        composeRule.onNodeWithTag(TrainingTestTags.ERROR).performClick()

        assertEquals(listOf<TrainingEvent>(TrainingEvent.DismissError), events)
    }

    @Test
    fun givenAMockPi_whenSimulateSwingIsTapped_thenARepIsSimulated() {
        show(previewTrainingState())

        composeRule.onNodeWithTag(TrainingTestTags.SIMULATE).performClick()

        assertEquals(listOf<TrainingEvent>(TrainingEvent.SimulateSwing), events)
    }

    @Test
    fun givenARealPi_whenShown_thenSimulateSwingIsHidden() {
        show(previewTrainingState().copy(showSimulateSwing = false))

        composeRule.onAllNodes(hasTestTag(TrainingTestTags.SIMULATE)).assertCountEquals(0)
    }
}
