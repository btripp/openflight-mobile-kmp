// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import dev.openflight.companion.core.model.GravitySample
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The tilt/roll pair shown on the calibration screen. [isStableAverage] is true when the angles
 * come from a send-ready measurement rather than the latest raw sample. Ported from
 * `ios/OpenFlight/PhoneOrientation.swift`.
 */
data class PhoneOrientationDisplayAngles(
    val mountTiltDegrees: Double,
    val rollDegrees: Double,
    val isStableAverage: Boolean,
)

/**
 * Pure orientation math, ported from `PhoneOrientationCalculator` in
 * `ios/OpenFlight/PhoneOrientation.swift` with the same thresholds.
 *
 * Samples must already use the CoreMotion gravity convention (see [GravitySample] and
 * [androidGravityToCoreMotion]): `tilt = asin(clamp(-z/|g|))` and `roll = atan2(x, -y)`, both in
 * degrees. The Pi recomputes exactly these formulas from `gravity_{x,y,z}_g`
 * (`src/openflight/phone_orientation.py`).
 */
object PhoneOrientationCalculator {
    /** Two seconds at 60 Hz gives a steady average without making setup feel slow. */
    const val MINIMUM_SAMPLE_COUNT = PhoneOrientationMeasurement.MINIMUM_SAMPLE_COUNT
    const val MAXIMUM_STANDARD_DEVIATION = PhoneOrientationMeasurement.MAXIMUM_STANDARD_DEVIATION_DEG
    const val MAXIMUM_ROLL_DEGREES = PhoneOrientationMeasurement.MAXIMUM_ROLL_DEG
    const val MAXIMUM_SETTLED_DISPLAY_DEVIATION_DEGREES = 0.5

    /** Samples whose magnitude falls outside this band (in g) are dropped before averaging. */
    const val MINIMUM_VALID_MAGNITUDE_G = 0.8
    const val MAXIMUM_VALID_MAGNITUDE_G = 1.2

    private const val DEGREES_PER_RADIAN = 180.0 / PI

    /**
     * Averages [samples] into a measurement, or returns null when fewer than
     * [MINIMUM_SAMPLE_COUNT] samples have a magnitude within 0.8–1.2 g. [measuredAt] is
     * formatted as ISO-8601 with whole seconds, matching Swift's `ISO8601DateFormatter`.
     *
     * Unlike the Swift default of `"iPhone"`, [deviceModel] has no default, so an Android caller
     * can't accidentally report itself as an iPhone.
     */
    @Suppress("ReturnCount") // Mirrors the Swift guard chain one-to-one.
    fun measurement(
        samples: List<GravitySample>,
        deviceModel: String,
        measuredAt: Instant = Clock.System.now(),
    ): PhoneOrientationMeasurement? {
        val valid = samples.filter { magnitude(it) in MINIMUM_VALID_MAGNITUDE_G..MAXIMUM_VALID_MAGNITUDE_G }
        if (valid.size < MINIMUM_SAMPLE_COUNT) return null

        val orientations = valid.mapNotNull(::sensorAngles)
        if (orientations.size != valid.size) return null

        val x = valid.sumOf { it.x } / valid.size
        val y = valid.sumOf { it.y } / valid.size
        val z = valid.sumOf { it.z } / valid.size
        val meanOrientation = sensorAngles(GravitySample(x, y, z)) ?: return null

        return PhoneOrientationMeasurement(
            mountTiltDeg = meanOrientation.mountTiltDegrees,
            rollDeg = meanOrientation.rollDegrees,
            gravityXG = x,
            gravityYG = y,
            gravityZG = z,
            tiltStddevDeg = standardDeviation(orientations.map { it.mountTiltDegrees }),
            rollStddevDeg = standardDeviation(orientations.map { it.rollDegrees }),
            sampleCount = valid.size,
            measuredAt = iso8601Seconds(measuredAt),
            deviceModel = deviceModel,
        )
    }

    /**
     * Shows the settled average once the measurement is send-ready and the live reading is within
     * 0.5° of it on both axes; otherwise shows the live angles of [latestSample].
     */
    fun displayAngles(
        latestSample: GravitySample,
        measurement: PhoneOrientationMeasurement?,
    ): PhoneOrientationDisplayAngles? {
        val latestAngles = sensorAngles(latestSample) ?: return null
        return if (measurement != null && isSettled(latestAngles, measurement)) {
            PhoneOrientationDisplayAngles(
                mountTiltDegrees = measurement.mountTiltDeg,
                rollDegrees = measurement.rollDeg,
                isStableAverage = true,
            )
        } else {
            latestAngles
        }
    }

    private fun isSettled(
        latest: PhoneOrientationDisplayAngles,
        measurement: PhoneOrientationMeasurement,
    ): Boolean =
        measurement.isReadyToSend &&
            abs(latest.mountTiltDegrees - measurement.mountTiltDeg) <= MAXIMUM_SETTLED_DISPLAY_DEVIATION_DEGREES &&
            abs(latest.rollDegrees - measurement.rollDeg) <= MAXIMUM_SETTLED_DISPLAY_DEVIATION_DEGREES

    private fun sensorAngles(sample: GravitySample): PhoneOrientationDisplayAngles? {
        val magnitude = magnitude(sample)
        if (magnitude <= 0.0) return null
        val normalizedZ = (-sample.z / magnitude).coerceIn(-1.0, 1.0)
        return PhoneOrientationDisplayAngles(
            mountTiltDegrees = asin(normalizedZ) * DEGREES_PER_RADIAN,
            rollDegrees = atan2(sample.x, -sample.y) * DEGREES_PER_RADIAN,
            isStableAverage = false,
        )
    }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val mean = values.sum() / values.size
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    private fun magnitude(sample: GravitySample): Double =
        sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)

    /** `ISO8601DateFormatter` default: `yyyy-MM-dd'T'HH:mm:ss'Z'`, no fractional seconds. */
    private fun iso8601Seconds(instant: Instant): String = Instant.fromEpochSeconds(instant.epochSeconds).toString()
}
