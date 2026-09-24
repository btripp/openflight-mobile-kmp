// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.GravitySample
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Instant

/** Pins the Android → CoreMotion conversion and the server's recomputation of the angles. */
class GravityConventionTest {
    private val g = 9.81

    private fun angles(sample: GravitySample): PhoneOrientationDisplayAngles =
        PhoneOrientationCalculator.displayAngles(sample, measurement = null)!!

    @Test
    fun portraitUprightAndroidReadingConvertsToCoreMotionMinusY() {
        val converted = androidGravityToCoreMotion(0.0, g, 0.0)

        assertThat(converted.x).isCloseTo(0.0, 1e-12)
        assertThat(converted.y).isCloseTo(-1.0, 0.001)
        assertThat(converted.z).isCloseTo(0.0, 1e-12)
        assertThat(angles(converted).mountTiltDegrees).isCloseTo(0.0, 1e-9)
        assertThat(angles(converted).rollDegrees).isCloseTo(0.0, 1e-9)
    }

    @Test
    fun faceUpAndroidReadingConvertsToCoreMotionMinusZ() {
        val converted = androidGravityToCoreMotion(0.0, 0.0, g)

        assertThat(converted.x).isCloseTo(0.0, 1e-12)
        assertThat(converted.y).isCloseTo(0.0, 1e-12)
        assertThat(converted.z).isCloseTo(-1.0, 0.001)
        assertThat(angles(converted).mountTiltDegrees).isCloseTo(90.0, 1e-9)
    }

    @Test
    fun standardGravityConvertsToExactlyOneG() {
        val converted = androidGravityToCoreMotion(0.0, STANDARD_GRAVITY_M_S2, 0.0)

        assertThat(converted.y).isEqualTo(-1.0)
    }

    /**
     * Derived rather than measured: with the right edge lowered, Android's support-reaction vector
     * gains a -x component, and CoreMotion's toward-Earth vector gains a +x component. Both give a
     * positive roll. Hardware must confirm this (BLOCKED-ON-HARDWARE).
     */
    @Test
    fun rightEdgeDownRollHasTheSameSignAfterConversion() {
        val roll = 2.0 * PI / 180
        val android = androidGravityToCoreMotion(-sin(roll) * g, cos(roll) * g, 0.0)

        assertThat(angles(android).rollDegrees).isCloseTo(2.0, 1e-9)
    }

    @Test
    fun lowPassFilterSeedsWithFirstReadingThenSmoothsWithAlphaPointEight() {
        val filter = LowPassGravityFilter()

        assertThat(filter.filter(0.0, 9.81, 0.0)).isEqualTo(Triple(0.0, 9.81, 0.0))
        val (x, y, z) = filter.filter(1.0, 9.81, 0.0)

        assertThat(x).isCloseTo(0.2, 1e-12)
        assertThat(y).isCloseTo(9.81, 1e-12)
        assertThat(z).isCloseTo(0.0, 1e-12)
    }

    /**
     * Reproduces `PhoneOrientationMeasurement.from_payload` in `src/openflight/phone_orientation.py`
     * for one Android-sourced measurement: the norm must lie within 0.9–1.1 g, the recomputed
     * tilt and roll must match the submitted ones within 0.25°, the tilt must lie within −30°..45°
     * and |roll| must be ≤ 3°.
     */
    @Test
    fun serverRecomputesTheSameAnglesFromAnAndroidSourcedPayload() {
        val tilt = 12.25 * PI / 180
        // An Android phone in portrait, top edge leaning back by 12.25° (screen facing up-ish).
        val androidReading = androidGravityToCoreMotion(0.0, cos(tilt) * g, sin(tilt) * g)
        val measurement =
            PhoneOrientationCalculator.measurement(
                samples = List(120) { androidReading },
                deviceModel = "Pixel 9",
                measuredAt = Instant.fromEpochSeconds(0),
            )
        assertThat(measurement).isNotNull()
        val payload = encode(measurement!!)

        val server = ServerRecomputation.from(payload)

        assertThat(server.gravityNorm).isCloseTo(1.0, 0.1)
        assertThat(abs(payload.number("mount_tilt_deg") - server.tiltDeg)).isLessThanOrEqualTo(0.25)
        assertThat(abs(payload.number("roll_deg") - server.rollDeg)).isLessThanOrEqualTo(0.25)
        assertThat(server.tiltDeg).isCloseTo(12.25, 0.0001)
        assertThat(server.rollDeg).isCloseTo(0.0, 0.0001)
        assertThat(server.tiltDeg in -30.0..45.0).isTrue()
        assertThat(abs(server.rollDeg) <= 3.0).isTrue()
        assertThat(payload["schema_version"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(payload["sample_count"]!!.jsonPrimitive.int).isEqualTo(120)
        assertThat(measurement.isReadyToSend).isTrue()
    }

    private fun encode(measurement: PhoneOrientationMeasurement): JsonObject =
        OpenFlightJson
            .parseToJsonElement(OpenFlightJson.encodeToString(PhoneOrientationMeasurement.serializer(), measurement))
            .jsonObject

    private fun JsonObject.number(key: String): Double = this[key]!!.jsonPrimitive.double

    /** A line-for-line transcription of the Python recomputation. */
    private data class ServerRecomputation(
        val gravityNorm: Double,
        val tiltDeg: Double,
        val rollDeg: Double,
    ) {
        companion object {
            fun from(payload: JsonObject): ServerRecomputation {
                val gx = payload["gravity_x_g"]!!.jsonPrimitive.double
                val gy = payload["gravity_y_g"]!!.jsonPrimitive.double
                val gz = payload["gravity_z_g"]!!.jsonPrimitive.double
                val norm = sqrt(gx * gx + gy * gy + gz * gz)
                val tilt = asin((-gz / norm).coerceIn(-1.0, 1.0)) * 180 / PI
                val roll = atan2(gx, -gy) * 180 / PI
                return ServerRecomputation(norm, tilt, roll)
            }
        }
    }
}
