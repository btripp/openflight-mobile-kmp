// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The calibration result returned by `iwr6843_orientation_calibration` control responses and
 * `POST /api/calibration/iwr6843/orientation`, ported from
 * `ios/OpenFlight/RadarCalibrationClient.swift`'s `RadarCalibrationResponse`.
 */
@Serializable
data class CalibrationResult(
    val status: String,
    val persistent: Boolean,
    @SerialName("measured_mount_tilt_deg") val measuredMountTiltDeg: Double,
    @SerialName("enclosure_pitch_deg") val enclosurePitchDeg: Double? = null,
    @SerialName("configured_iwr_tilt_deg") val configuredIwrTiltDeg: Double,
    @SerialName("roll_deg") val rollDeg: Double,
    @SerialName("azimuth_offset_deg") val azimuthOffsetDeg: Double,
)
