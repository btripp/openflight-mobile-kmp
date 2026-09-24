// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.sensors.PhoneOrientationDisplayAngles
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CalibrationScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<CalibrationEvent>()

    private fun show(state: CalibrationUiState) {
        composeRule.setContent {
            OfTheme {
                CalibrationScreen(uiState = state, onEvent = { events += it }, onBack = {})
            }
        }
    }

    @Test
    fun given_readyMeasurementOverWifi_whenShown_thenApplyIsEnabled() {
        show(readyState(transport = TransportType.WIFI))

        composeRule.onNodeWithTag(CalibrationTestTags.APPLY).assertIsEnabled()
    }

    @Test
    fun given_readyMeasurementOverBluetoothWithoutControls_whenShown_thenApplyIsDisabled() {
        show(readyState(transport = TransportType.BLUETOOTH, bluetoothReady = false))

        composeRule.onNodeWithTag(CalibrationTestTags.APPLY).assertIsNotEnabled()
    }

    @Test
    fun given_readyMeasurementOverBluetoothWithControls_whenShown_thenApplyIsEnabled() {
        show(readyState(transport = TransportType.BLUETOOTH, bluetoothReady = true))

        composeRule.onNodeWithTag(CalibrationTestTags.APPLY).assertIsEnabled()
    }

    @Test
    fun given_aSubmissionInFlight_whenShown_thenApplyIsDisabled() {
        show(readyState().copy(submit = SubmitUiState.Submitting))

        composeRule.onNodeWithTag(CalibrationTestTags.APPLY).assertIsNotEnabled()
    }

    @Test
    fun given_motionUnavailable_whenShown_thenTheMessageIsDisplayed() {
        show(
            CalibrationUiState(
                sensor = SensorUiState.Unavailable("Motion unavailable"),
                submit = SubmitUiState.Idle,
                transport = TransportType.WIFI,
                host = "raspberrypi.local:8080",
                bluetoothReady = false,
            ),
        )

        composeRule.onNodeWithTag(CalibrationTestTags.MOTION_UNAVAILABLE).assertIsDisplayed()
        composeRule.onNodeWithText("Motion unavailable").assertIsDisplayed()
    }

    @Test
    fun given_apply_whenTapped_thenAnApplyEventIsSent() {
        show(readyState())

        composeRule.onNodeWithTag(CalibrationTestTags.APPLY).performClick()

        assertEquals(listOf<CalibrationEvent>(CalibrationEvent.Apply), events)
    }

    @Test
    fun given_anAppliedResult_whenShown_thenTheSavedTiltIsDisplayed() {
        show(readyState().copy(submit = SubmitUiState.Applied(fullResult)))

        composeRule.onNodeWithTag(CalibrationTestTags.APPLIED_RESULT).assertIsDisplayed()
        composeRule.onNodeWithText("Saved TI tilt: 4.00°").assertIsDisplayed()
    }

    @Test
    fun given_aFailedSubmission_whenShown_thenTheServerMessageIsDisplayed() {
        show(readyState().copy(submit = SubmitUiState.Failed("TI IWR6843 radar is not enabled")))

        composeRule.onNodeWithTag(CalibrationTestTags.SUBMIT_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("TI IWR6843 radar is not enabled").assertIsDisplayed()
    }

    @Test
    fun given_wifi_whenShown_thenTheHostFieldShowsAndBluetoothCardIsHidden() {
        show(readyState(transport = TransportType.WIFI))

        composeRule.onNodeWithTag(CalibrationTestTags.HOST_FIELD).assertIsDisplayed()
        composeRule.onNodeWithText("raspberrypi.local:8080").assertIsDisplayed()
    }

    @Test
    fun given_bluetooth_whenShown_thenTheBluetoothCardShows() {
        show(readyState(transport = TransportType.BLUETOOTH, bluetoothReady = true))

        composeRule.onNodeWithTag(CalibrationTestTags.BLUETOOTH_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Connected and ready to calibrate").assertIsDisplayed()
    }

    private fun readyState(
        transport: TransportType = TransportType.WIFI,
        bluetoothReady: Boolean = false,
    ) = CalibrationUiState(
        sensor =
            SensorUiState.Sampling(
                displayAngles =
                    PhoneOrientationDisplayAngles(
                        mountTiltDegrees = 4.0,
                        rollDegrees = 0.5,
                        isStableAverage = true,
                    ),
                progress = 1.0,
                sampleCount = 120,
                measurement = readyMeasurement,
            ),
        submit = SubmitUiState.Idle,
        transport = transport,
        host = "raspberrypi.local:8080",
        bluetoothReady = bluetoothReady,
    )

    private companion object {
        val readyMeasurement =
            PhoneOrientationMeasurement(
                mountTiltDeg = 4.0,
                rollDeg = 0.5,
                gravityXG = 0.0,
                gravityYG = -1.0,
                gravityZG = -0.07,
                tiltStddevDeg = 0.1,
                rollStddevDeg = 0.1,
                sampleCount = 120,
                measuredAt = "2026-01-01T00:00:00Z",
                deviceModel = "Pixel",
            )
        val fullResult =
            CalibrationResult(
                status = "ok",
                persistent = true,
                measuredMountTiltDeg = 4.0,
                enclosurePitchDeg = 1.0,
                configuredIwrTiltDeg = 4.0,
                rollDeg = 0.5,
                azimuthOffsetDeg = 0.0,
            )
    }
}
