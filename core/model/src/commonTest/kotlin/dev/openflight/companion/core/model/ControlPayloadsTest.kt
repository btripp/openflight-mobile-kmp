// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
import kotlin.test.Test

private val json = Json { encodeDefaults = true }

class ControlPayloadsTest {
    @Test
    fun clubSelectionDecodesTheClubResultShape() {
        val decoded =
            json.decodeFromString(
                ClubSelection.serializer(),
                """{"status":"ok","club":"7-iron"}""",
            )

        assertThat(decoded.status).isEqualTo("ok")
        assertThat(decoded.club).isEqualTo(GolfClub.IRON_7)
    }

    @Test
    fun calibrationResultDecodesWithOptionalEnclosurePitch() {
        val decoded =
            json.decodeFromString(
                CalibrationResult.serializer(),
                """
                {
                  "status": "ok",
                  "persistent": true,
                  "measured_mount_tilt_deg": 12.3,
                  "enclosure_pitch_deg": null,
                  "configured_iwr_tilt_deg": 12.3,
                  "roll_deg": 0.4,
                  "azimuth_offset_deg": 0.0
                }
                """.trimIndent(),
            )

        assertThat(decoded.enclosurePitchDeg).isEqualTo(null)
        assertThat(decoded.measuredMountTiltDeg).isEqualTo(12.3)
    }

    @Test
    fun phoneOrientationMeasurementEncodesSchemaVersionAndExactWireKeys() {
        val measurement =
            PhoneOrientationMeasurement(
                mountTiltDeg = 12.0,
                rollDeg = 0.5,
                gravityXG = 0.01,
                gravityYG = -0.02,
                gravityZG = -0.99,
                tiltStddevDeg = 0.1,
                rollStddevDeg = 0.2,
                sampleCount = 120,
                measuredAt = "2026-08-05T23:54:00Z",
                deviceModel = "iPhone",
            )

        val encoded = json.encodeToString(PhoneOrientationMeasurement.serializer(), measurement)

        assertThat(encoded).contains("\"schema_version\":1")
        assertThat(encoded).contains("\"mount_tilt_deg\":12.0")
        assertThat(encoded).contains("\"roll_deg\":0.5")
        assertThat(encoded).contains("\"gravity_x_g\":0.01")
        assertThat(encoded).contains("\"gravity_y_g\":-0.02")
        assertThat(encoded).contains("\"gravity_z_g\":-0.99")
        assertThat(encoded).contains("\"tilt_stddev_deg\":0.1")
        assertThat(encoded).contains("\"roll_stddev_deg\":0.2")
        assertThat(encoded).contains("\"sample_count\":120")
        assertThat(encoded).contains("\"measured_at\":\"2026-08-05T23:54:00Z\"")
        assertThat(encoded).contains("\"device_model\":\"iPhone\"")
    }

    @Test
    fun isReadyToSendRequiresAllThresholdsAtOnce() {
        val base =
            PhoneOrientationMeasurement(
                mountTiltDeg = 10.0,
                rollDeg = 0.0,
                gravityXG = 0.0,
                gravityYG = 0.0,
                gravityZG = -1.0,
                tiltStddevDeg = 0.1,
                rollStddevDeg = 0.1,
                sampleCount = 120,
                measuredAt = "2026-08-05T23:54:00Z",
                deviceModel = "iPhone",
            )
        assertThat(base.isReadyToSend).isTrue()

        assertThat(base.copy(sampleCount = 119).isReadyToSend).isFalse()
        assertThat(base.copy(tiltStddevDeg = 0.51).isReadyToSend).isFalse()
        assertThat(base.copy(rollStddevDeg = 0.51).isReadyToSend).isFalse()
        assertThat(base.copy(rollDeg = 3.01).isReadyToSend).isFalse()
        assertThat(base.copy(rollDeg = -3.01).isReadyToSend).isFalse()
        assertThat(base.copy(mountTiltDeg = -30.1).isReadyToSend).isFalse()
        assertThat(base.copy(mountTiltDeg = 45.1).isReadyToSend).isFalse()
        assertThat(base.copy(mountTiltDeg = -30.0).isReadyToSend).isTrue()
        assertThat(base.copy(mountTiltDeg = 45.0).isReadyToSend).isTrue()
    }
}
