// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.designsystem.OfButton
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfScaffold
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextField
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.designsystem.OfTopBar
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.sensors.PhoneOrientationDisplayAngles

/**
 * The calibration screen (RadarCalibrationView.swift): instructions, the active transport's
 * readiness card, the live/stable angle readout and the Apply action. Stateless: everything comes
 * from [uiState] and every interaction goes out through [onEvent] or [onBack]. The measurement
 * card's composables live in `CalibrationMeasurementCard.kt` to stay under detekt's per-file
 * function limit.
 */
@Composable
fun CalibrationScreen(
    uiState: CalibrationUiState,
    onEvent: (CalibrationEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn(enabled = uiState.sensor is SensorUiState.Sampling)
    OfScaffold(
        modifier = modifier,
        topBar = {
            OfTopBar(
                title = "Calibrate TI Radar",
                eyebrow = "OPENFLIGHT",
                actions = { OfOutlinedButton(text = "Done", onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = OfSpacing.Xl, vertical = OfSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OfSpacing.Xl),
        ) {
            InstructionsCard()
            TransportCard(uiState, onEvent)
            MeasurementCard(uiState.sensor)
            SubmissionCard(uiState, onEvent)
        }
    }
}

@Composable
private fun InstructionsCard() {
    OfCard(modifier = Modifier.fillMaxWidth()) {
        OfText(text = "MEASURE THE RADAR FACE", role = OfTextRole.Eyebrow, color = OfColorTokens.Gold)
        OfText(
            text =
                "Remove the case. Hold the phone upright in portrait with its back flat against a " +
                    "straight reference surface parallel to the TI antenna face. Keep the screen facing the target.",
            role = OfTextRole.Body,
        )
        OfText(
            text = "Avoid the camera bump and keep both the radar and phone still while the two-second sample fills.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
        )
        OfText(
            text = "This calibrates gravity-referenced mount tilt. It does not change target-line azimuth.",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.Warning,
        )
    }
}

@Composable
private fun TransportCard(
    uiState: CalibrationUiState,
    onEvent: (CalibrationEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth()) {
        if (uiState.transport == TransportType.WIFI) {
            OfText(text = "OPENFLIGHT PI", role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
            OfTextField(
                value = uiState.host,
                onValueChange = { onEvent(CalibrationEvent.HostEdited(it)) },
                placeholder = SettingsRepository.DEFAULT_HOST,
                monospace = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                onSubmit = { onEvent(CalibrationEvent.HostSubmitted) },
                modifier = Modifier.fillMaxWidth().testTag(CalibrationTestTags.HOST_FIELD),
            )
        } else {
            OfText(text = "OPENFLIGHT BLUETOOTH", role = OfTextRole.Eyebrow, color = OfColorTokens.CreamDim)
            OfText(
                text =
                    if (uiState.bluetoothReady) {
                        "Connected and ready to calibrate"
                    } else {
                        "Connect to an updated OpenFlight Pi"
                    },
                role = OfTextRole.BodySmall,
                color = if (uiState.bluetoothReady) OfColorTokens.Success else OfColorTokens.Warning,
                modifier = Modifier.testTag(CalibrationTestTags.BLUETOOTH_CARD),
            )
        }
    }
}

@Composable
private fun SubmissionCard(
    uiState: CalibrationUiState,
    onEvent: (CalibrationEvent) -> Unit,
) {
    OfCard(modifier = Modifier.fillMaxWidth()) {
        OfButton(
            text = "Apply Calibration",
            onClick = { onEvent(CalibrationEvent.Apply) },
            enabled = uiState.applyEnabled,
            loading = uiState.submit is SubmitUiState.Submitting,
            modifier = Modifier.fillMaxWidth().testTag(CalibrationTestTags.APPLY),
        )
        when (val submit = uiState.submit) {
            is SubmitUiState.Applied -> {
                OfText(
                    text =
                        "Saved TI tilt: ${ShotMetricFormatter.number(submit.result.configuredIwrTiltDeg, DECIMALS)}°",
                    role = OfTextRole.TitleSmall,
                    color = OfColorTokens.Success,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(CalibrationTestTags.APPLIED_RESULT),
                )
                OfText(
                    text = responseSummary(submit.result),
                    role = OfTextRole.BodySmall,
                    color = OfColorTokens.CreamDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SubmitUiState.Failed -> {
                OfText(
                    text = submit.message,
                    role = OfTextRole.Body,
                    color = OfColorTokens.Danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(CalibrationTestTags.SUBMIT_ERROR),
                )
            }

            SubmitUiState.Idle, SubmitUiState.Submitting -> {
                Unit
            }
        }
    }
}

/** RadarCalibrationView's `responseSummary(_:)`. */
private fun responseSummary(result: CalibrationResult): String {
    val enclosurePitch =
        result.enclosurePitchDeg
            ?: return "The measured phone tilt is now active and will be restored after restart."
    return "Measured ${ShotMetricFormatter.number(result.measuredMountTiltDeg, DECIMALS)}° minus enclosure pitch " +
        "${ShotMetricFormatter.number(enclosurePitch, DECIMALS)}°."
}

/**
 * Decimal places for angle/tilt display, matching Swift's `.fractionLength(2)`. Shared with
 * `CalibrationMeasurementCard.kt`.
 */
internal const val DECIMALS = 2

@Preview
@Composable
private fun CalibrationSamplingPreview() {
    OfTheme {
        CalibrationScreen(
            uiState =
                CalibrationUiState(
                    sensor =
                        SensorUiState.Sampling(
                            displayAngles = PhoneOrientationDisplayAngles(4.8, 1.1, false),
                            progress = 0.7,
                            sampleCount = 84,
                            measurement = null,
                        ),
                    submit = SubmitUiState.Idle,
                    transport = TransportType.WIFI,
                    host = "raspberrypi.local:8080",
                    bluetoothReady = false,
                ),
            onEvent = {},
            onBack = {},
        )
    }
}
