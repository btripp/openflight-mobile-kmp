// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * One raw gravity reading in the iOS CoreMotion convention: units of g, the vector points toward
 * Earth, and a phone lying face-up reads `z ≈ -1`. Android's `expect`/`actual` gravity sensor
 * (core:sensors, step 8b) must convert into this convention before producing samples the
 * calculator can use. Ported from `ios/OpenFlight/RadarCalibrationClient.swift`'s `GravitySample`.
 */
data class GravitySample(
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * The averaged calibration measurement sent to the Pi, ported from
 * `ios/OpenFlight/RadarCalibrationClient.swift`'s `PhoneOrientationMeasurement`. The sampling and
 * averaging math that produces one of these lives in `core:sensors` (step 8b); this type only
 * carries the wire payload and the send-readiness check, which depends solely on the measurement
 * itself.
 */
@Serializable
data class PhoneOrientationMeasurement(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("mount_tilt_deg") val mountTiltDeg: Double,
    @SerialName("roll_deg") val rollDeg: Double,
    @SerialName("gravity_x_g") val gravityXG: Double,
    @SerialName("gravity_y_g") val gravityYG: Double,
    @SerialName("gravity_z_g") val gravityZG: Double,
    @SerialName("tilt_stddev_deg") val tiltStddevDeg: Double,
    @SerialName("roll_stddev_deg") val rollStddevDeg: Double,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("device_model") val deviceModel: String,
) {
    /**
     * True once there are enough samples, both standard deviations are tight, roll is close to
     * level, and tilt is within the mount's physical range. Matches
     * `PhoneOrientationMeasurement.isReadyToSend` in the reference exactly.
     */
    val isReadyToSend: Boolean
        get() =
            sampleCount >= MINIMUM_SAMPLE_COUNT &&
                tiltStddevDeg <= MAXIMUM_STANDARD_DEVIATION_DEG &&
                rollStddevDeg <= MAXIMUM_STANDARD_DEVIATION_DEG &&
                abs(rollDeg) <= MAXIMUM_ROLL_DEG &&
                mountTiltDeg >= MINIMUM_TILT_DEG &&
                mountTiltDeg <= MAXIMUM_TILT_DEG

    companion object {
        /** Two seconds at 60 Hz gives a steady average without making setup feel slow. */
        const val MINIMUM_SAMPLE_COUNT = 120
        const val MAXIMUM_STANDARD_DEVIATION_DEG = 0.5
        const val MAXIMUM_ROLL_DEG = 3.0
        const val MINIMUM_TILT_DEG = -30.0
        const val MAXIMUM_TILT_DEG = 45.0
    }
}
