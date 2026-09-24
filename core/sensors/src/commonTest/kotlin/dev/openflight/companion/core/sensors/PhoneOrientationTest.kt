// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.sensors

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.GravitySample
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.protocol.ControlCodec
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Instant

/**
 * One-to-one port of `ios/OpenFlightTests/PhoneOrientationTests.swift`, keeping its numbers.
 * Test names drop the Swift `test` prefix.
 *
 * Seven Swift cases exercise transport encoders that live outside this module. The ones that
 * `core:protocol` already implements are ported here against [ControlCodec]/[OpenFlightJson]. The
 * Wi-Fi URL/method/header assertions need S4's `core:network` client and are listed where they
 * would go.
 */
class PhoneOrientationTest {
    private fun radians(degrees: Double) = degrees * PI / 180

    private fun tiltSample(degrees: Double): GravitySample {
        val tilt = radians(degrees)
        return GravitySample(x = 0.0, y = -cos(tilt), z = -sin(tilt))
    }

    private fun alternating10And12(): List<GravitySample> =
        (0 until 120).map { index ->
            tiltSample(
                if (index % 2 ==
                    0
                ) {
                    10.0
                } else {
                    12.0
                },
            )
        }

    private fun measurementOf(
        samples: List<GravitySample>,
        measuredAt: Instant = Instant.fromEpochSeconds(0),
    ): PhoneOrientationMeasurement {
        val measurement =
            PhoneOrientationCalculator.measurement(
                samples,
                deviceModel = "iPhone",
                measuredAt = measuredAt,
            )
        assertThat(measurement).isNotNull()
        return measurement!!
    }

    private fun portraitMeasurement() = measurementOf(List(120) { GravitySample(0.0, -1.0, 0.0) })

    private fun ByteArray.toJsonObject(): JsonObject = OpenFlightJson.parseToJsonElement(decodeToString()).jsonObject

    @Test
    fun displayUsesLatestSensorAnglesWhileWindowIsUnstable() {
        val measurement = measurementOf(alternating10And12())
        val latest = tiltSample(24.0)

        val display = PhoneOrientationCalculator.displayAngles(latest, measurement)

        assertThat(measurement.isReadyToSend).isFalse()
        assertThat(display).isNotNull()
        assertThat(display!!.mountTiltDegrees).isCloseTo(24.0, 0.0001)
        assertThat(display.isStableAverage).isFalse()
    }

    @Test
    fun displayUsesCalibrationAverageAfterItSettles() {
        val sample = tiltSample(12.25)
        val measurement = measurementOf(List(120) { sample })

        val display = PhoneOrientationCalculator.displayAngles(sample, measurement)

        assertThat(measurement.isReadyToSend).isTrue()
        assertThat(display).isNotNull()
        assertThat(display!!.mountTiltDegrees).isCloseTo(measurement.mountTiltDeg, 0.0001)
        assertThat(display.rollDegrees).isCloseTo(measurement.rollDeg, 0.0001)
        assertThat(display.isStableAverage).isTrue()
    }

    @Test
    fun displayImmediatelyReturnsToLiveWhenPhoneMovesAfterSettling() {
        val measurement = measurementOf(List(120) { tiltSample(12.25) })
        val moved = tiltSample(20.0)

        val display = PhoneOrientationCalculator.displayAngles(moved, measurement)

        assertThat(measurement.isReadyToSend).isTrue()
        assertThat(display).isNotNull()
        assertThat(display!!.mountTiltDegrees).isCloseTo(20.0, 0.0001)
        assertThat(display.isStableAverage).isFalse()
    }

    @Test
    fun portraitPhoneAgainstVerticalFaceReadsZeroTiltAndRoll() {
        val samples = List(PhoneOrientationCalculator.MINIMUM_SAMPLE_COUNT) { GravitySample(0.0, -1.0, 0.0) }

        val measurement = measurementOf(samples, measuredAt = Instant.fromEpochSeconds(0))

        assertThat(measurement.mountTiltDeg).isCloseTo(0.0, 0.0001)
        assertThat(measurement.rollDeg).isCloseTo(0.0, 0.0001)
        assertThat(measurement.isReadyToSend).isTrue()
    }

    @Test
    fun gravityVectorProducesRadarTiltAndRoll() {
        val tilt = radians(12.25)
        val roll = radians(-1.5)
        val horizontal = cos(tilt)
        val sample = GravitySample(x = sin(roll) * horizontal, y = -cos(roll) * horizontal, z = -sin(tilt))

        val measurement = measurementOf(List(120) { sample })

        assertThat(measurement.mountTiltDeg).isCloseTo(12.25, 0.0001)
        assertThat(measurement.rollDeg).isCloseTo(-1.5, 0.0001)
        assertThat(measurement.gravityXG).isCloseTo(sample.x, 0.000001)
        assertThat(measurement.isReadyToSend).isTrue()
    }

    @Test
    fun movingPhoneIsNotReadyToSend() {
        val measurement = measurementOf(alternating10And12())

        assertThat(measurement.tiltStddevDeg).isGreaterThan(0.5)
        assertThat(measurement.isReadyToSend).isFalse()
    }

    @Test
    fun badlyRolledRadarIsNotReadyToSend() {
        val roll = radians(4.0)
        val sample = GravitySample(x = sin(roll), y = -cos(roll), z = 0.0)

        val measurement = measurementOf(List(120) { sample })

        assertThat(measurement.rollDeg).isCloseTo(4.0, 0.0001)
        assertThat(measurement.isReadyToSend).isFalse()
    }

    /**
     * Payload half of `testCalibrationRequestUsesWiFiHostAndSnakeCasePayload`. The URL
     * (`http://raspberrypi.local:8080/api/calibration/iwr6843/orientation`), `POST` and
     * `Content-Type: application/json` assertions belong to S4's `core:network` calibration client.
     */
    @Test
    fun calibrationRequestUsesSnakeCasePayload() {
        val measurement = portraitMeasurement()

        val body =
            OpenFlightJson
                .encodeToString(PhoneOrientationMeasurement.serializer(), measurement)
                .encodeToByteArray()
                .toJsonObject()

        assertThat(body["schema_version"]!!.jsonPrimitive.int).isEqualTo(1)
        // asin(-0.0) is -0.0. Swift's `== 0` is IEEE equality, so compare with a zero delta
        // instead of Double.equals.
        assertThat(body["mount_tilt_deg"]!!.jsonPrimitive.double).isCloseTo(0.0, 0.0)
        assertThat(body["sample_count"]!!.jsonPrimitive.int).isEqualTo(120)
        assertThat(body["device_model"]!!.jsonPrimitive.content).isEqualTo("iPhone")
        // Swift's ISO8601DateFormatter output for Date(timeIntervalSince1970: 0).
        assertThat(body["measured_at"]!!.jsonPrimitive.content).isEqualTo("1970-01-01T00:00:00Z")
    }

    @Test
    fun bluetoothCalibrationCommandWrapsSharedMeasurementPayload() {
        val measurement = portraitMeasurement()

        val command = ControlCodec.encodeCalibration(measurement, requestId = "request-1").toJsonObject()
        val payload = command["payload"]!!.jsonObject

        assertThat(command["schema_version"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(command["type"]!!.jsonPrimitive.content).isEqualTo("iwr6843_orientation_calibration")
        assertThat(command["request_id"]!!.jsonPrimitive.content).isEqualTo("request-1")
        assertThat(payload["sample_count"]!!.jsonPrimitive.int).isEqualTo(120)
        assertThat(payload["mount_tilt_deg"]!!.jsonPrimitive.double).isCloseTo(0.0, 0.0)
    }

    @Test
    fun bluetoothClubCommandUsesSharedControlEnvelope() {
        val command = ControlCodec.encodeSetClub(GolfClub.IRON_7, requestId = "club-request-1").toJsonObject()
        val payload = command["payload"]!!.jsonObject

        assertThat(command["schema_version"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(command["type"]!!.jsonPrimitive.content).isEqualTo("set_club")
        assertThat(command["request_id"]!!.jsonPrimitive.content).isEqualTo("club-request-1")
        assertThat(payload["club"]!!.jsonPrimitive.content).isEqualTo("7-iron")
    }

    @Test
    fun bluetoothCurrentClubCommandUsesSharedControlEnvelope() {
        val command = ControlCodec.encodeGetClub(requestId = "club-current-1").toJsonObject()

        assertThat(command["schema_version"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(command["type"]!!.jsonPrimitive.content).isEqualTo("get_club")
        assertThat(command["request_id"]!!.jsonPrimitive.content).isEqualTo("club-current-1")
        assertThat(command["payload"]!!.jsonObject.size).isEqualTo(0)
    }

    // Not portable here (need S4's core:network, which doesn't exist on main yet):
    // - testCalibrationURLRejectsUnsupportedHost
    // - testWiFiClubRequestUsesClubEndpoint
    // - testWiFiCurrentClubRequestReadsClubEndpoint
    // - the URL/method/header half of testCalibrationRequestUsesWiFiHostAndSnakeCasePayload
}
