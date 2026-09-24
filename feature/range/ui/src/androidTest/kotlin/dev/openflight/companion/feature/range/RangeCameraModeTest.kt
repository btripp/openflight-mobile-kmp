// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The camera-mode toggle (plan R7a), on the stateless screen and through the route's real view model. */
@RunWith(AndroidJUnit4::class)
class RangeCameraModeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun givenTheFollowCamera_whenTheToggleIsTapped_thenToggleCameraModeIsSent() {
        val events = mutableListOf<DrivingRangeEvent>()
        composeRule.setContent {
            OfTheme {
                DrivingRangeScreen(
                    uiState = DrivingRangeUiState.Ready(camera = RangeCameraState(RangeCameraMode.FOLLOW)),
                    reduceMotion = false,
                    onEvent = { events += it },
                    onExit = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(RangeTestTags.CAMERA_MODE)
            .assert(hasText("Follow"))
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf<DrivingRangeEvent>(DrivingRangeEvent.ToggleCameraMode), events)
    }

    @Test
    fun givenReducedMotion_whenShown_thenTheCameraIsFixedAndTheToggleIsDisabled() {
        composeRule.setContent {
            OfTheme {
                DrivingRangeScreen(
                    uiState =
                        DrivingRangeUiState.Ready(camera = RangeCameraState(RangeCameraMode.FIXED, locked = true)),
                    reduceMotion = true,
                    onEvent = {},
                    onExit = {},
                )
            }
        }

        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Fixed")).assertIsNotEnabled()
    }

    @Test
    fun givenTheRange_whenTheCameraIsToggledAndTheRangeReopened_thenTheChoiceIsKept() {
        val settings = CameraSettings()
        val shots = FakeShotRepository()
        var inRange by mutableStateOf(true)
        composeRule.setContent {
            OfTheme {
                if (inRange) {
                    // A fresh view model per visit, like a new navigation entry.
                    val viewModel = remember { DrivingRangeViewModel(shots, settings) }
                    DrivingRangeRoute(onExit = { inRange = false }, viewModel = viewModel, reduceMotion = false)
                } else {
                    Box {
                        OfOutlinedButton(
                            text = "Driving range",
                            onClick = { inRange = true },
                            modifier = Modifier.testTag(ENTER),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Follow")).performClick()
        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Fixed"))
        assertEquals(RangeCameraMode.FIXED, settings.rangeCameraMode.value)

        composeRule.onNodeWithTag(RangeTestTags.EXIT).performClick()
        composeRule.onNodeWithTag(ENTER).performClick()

        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Fixed")).performClick()
        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Follow"))
        assertEquals(RangeCameraMode.FOLLOW, settings.rangeCameraMode.value)
    }

    @Test
    fun givenReducedMotion_whenTheRangeOpens_thenTheStoredFollowCameraIsOverriddenAndLocked() {
        val settings = CameraSettings()
        composeRule.setContent {
            OfTheme {
                val viewModel = remember { DrivingRangeViewModel(FakeShotRepository(), settings) }
                DrivingRangeRoute(onExit = {}, viewModel = viewModel, reduceMotion = true)
            }
        }

        composeRule.onNodeWithTag(RangeTestTags.CAMERA_MODE).assert(hasText("Fixed")).assertIsNotEnabled()
        assertEquals(RangeCameraMode.FOLLOW, settings.rangeCameraMode.value)
    }

    /** In-memory settings that keep the camera mode (the shared fake predates it). */
    private class CameraSettings(
        base: FakeSettingsRepository = FakeSettingsRepository(),
    ) : SettingsRepository by base {
        override val rangeCameraMode = MutableStateFlow(SettingsRepository.DEFAULT_RANGE_CAMERA_MODE)

        override suspend fun setRangeCameraMode(mode: RangeCameraMode) {
            rangeCameraMode.value = mode
        }
    }

    private companion object {
        const val ENTER = "test.enterRange"
    }
}
