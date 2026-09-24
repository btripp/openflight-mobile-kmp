// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfCard
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfLinearProgress
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.sensors.PhoneOrientationDisplayAngles
import kotlin.math.abs
import kotlin.math.max

/**
 * The live/stable angle readout card, split out of `CalibrationScreen.kt` to stay under detekt's
 * per-file function limit.
 */
@Composable
internal fun MeasurementCard(sensor: SensorUiState) {
    OfCard(modifier = Modifier.fillMaxWidth()) {
        when (sensor) {
            is SensorUiState.Unavailable -> {
                OfText(
                    text = sensor.message,
                    role = OfTextRole.Title,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                            .testTag(CalibrationTestTags.MOTION_UNAVAILABLE),
                )
            }

            is SensorUiState.Sampling -> {
                SamplingContent(sensor)
            }
        }
    }
}

@Composable
private fun SamplingContent(sensor: SensorUiState.Sampling) {
    val angles = sensor.displayAngles
    if (angles == null) {
        OfText(
            text = "Hold still while OpenFlight averages the sensors",
            role = OfTextRole.Title,
            textAlign = TextAlign.Center,
        )
        OfLinearProgress(progress = sensor.progress.toFloat(), modifier = Modifier.fillMaxWidth())
        OfText(
            text = "${sensor.sampleCount} / ${PhoneOrientationMeasurement.MINIMUM_SAMPLE_COUNT} samples",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
        )
        return
    }

    OfText(
        text = if (angles.isStableAverage) "STABLE 2-SECOND AVERAGE" else "LIVE SENSOR READING",
        role = OfTextRole.Eyebrow,
        color = if (angles.isStableAverage) OfColorTokens.Success else OfColorTokens.Info,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(OfSpacing.Md)) {
        AngleMetric(
            title = "MOUNT TILT",
            degrees = angles.mountTiltDegrees,
            color = if (angles.isStableAverage) OfColorTokens.Success else OfColorTokens.Info,
            modifier = Modifier.weight(1f).testTag(CalibrationTestTags.TILT_METRIC),
        )
        AngleMetric(
            title = "LEFT / RIGHT ROLL",
            degrees = angles.rollDegrees,
            color = rollColor(angles),
            modifier = Modifier.weight(1f).testTag(CalibrationTestTags.ROLL_METRIC),
        )
    }

    val measurement = sensor.measurement
    if (measurement == null) {
        OfLinearProgress(
            progress = sensor.progress.toFloat(),
            modifier = Modifier.fillMaxWidth().testTag(CalibrationTestTags.PROGRESS),
        )
        OfText(
            text =
                "Collecting calibration average: ${sensor.sampleCount} / " +
                    "${PhoneOrientationMeasurement.MINIMUM_SAMPLE_COUNT} samples",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
        )
    } else {
        OfText(
            text = readinessMessage(measurement),
            role = OfTextRole.TitleSmall,
            color = if (measurement.isReadyToSend) OfColorTokens.Success else OfColorTokens.Warning,
        )
        val stability = max(measurement.tiltStddevDeg, measurement.rollStddevDeg)
        OfText(
            text =
                "Stability ±${ShotMetricFormatter.number(
                    stability,
                    DECIMALS,
                )}° from ${measurement.sampleCount} samples",
            role = OfTextRole.BodySmall,
            color = OfColorTokens.CreamDim,
        )
    }
}

private fun rollColor(angles: PhoneOrientationDisplayAngles) =
    if (abs(angles.rollDegrees) <= PhoneOrientationMeasurement.MAXIMUM_ROLL_DEG) {
        if (angles.isStableAverage) OfColorTokens.Success else OfColorTokens.Info
    } else {
        OfColorTokens.Warning
    }

/** RadarCalibrationView's `readinessMessage(_:)`. */
private fun readinessMessage(measurement: PhoneOrientationMeasurement): String =
    when {
        abs(measurement.rollDeg) > PhoneOrientationMeasurement.MAXIMUM_ROLL_DEG -> {
            "Level the radar left-to-right within 3°"
        }

        measurement.tiltStddevDeg > PhoneOrientationMeasurement.MAXIMUM_STANDARD_DEVIATION_DEG ||
            measurement.rollStddevDeg > PhoneOrientationMeasurement.MAXIMUM_STANDARD_DEVIATION_DEG -> {
            "Keep the phone and radar still"
        }

        else -> {
            if (measurement.isReadyToSend) "Stable measurement ready" else "Adjust the radar angle"
        }
    }

@Composable
private fun AngleMetric(
    title: String,
    degrees: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(OfColorTokens.BgElevated, RoundedCornerShape(OfSpacing.Md))
                .padding(OfSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(OfSpacing.Xs),
    ) {
        OfText(text = title, role = OfTextRole.Label, color = OfColorTokens.CreamDim)
        OfText(text = "${ShotMetricFormatter.number(degrees, DECIMALS)}°", role = OfTextRole.Headline, color = color)
    }
}
