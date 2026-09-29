// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import kotlin.test.Test

/** The BLE control commands (schema 2 only) and the response/`club_changed` decoders. */
class ControlCodecTest {
    @Test
    fun encodeSetClubIsASortedSchema2Envelope() {
        val encoded = SchemaV2Codec.encodeSetClub(GolfClub.IRON_7, requestId = "req-1").decodeToString()

        assertThat(encoded).isEqualTo(
            """{"payload":{"club":"7-iron"},"request_id":"req-1","schema_version":2,"type":"set_club"}""",
        )
    }

    @Test
    fun encodeGetClubIsASortedSchema2Envelope() {
        val encoded = SchemaV2Codec.encodeGetClub(requestId = "req-2").decodeToString()

        assertThat(encoded)
            .isEqualTo("""{"payload":{},"request_id":"req-2","schema_version":2,"type":"get_club"}""")
    }

    @Test
    fun encodeCalibrationContainsSchemaVersionAndTheExactWireKeys() {
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

        val encoded = SchemaV2Codec.encodeCalibration(measurement, requestId = "req-3").decodeToString()

        assertThat(encoded).contains("\"schema_version\":2")
        assertThat(encoded).contains("\"type\":\"iwr6843_orientation_calibration\"")
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
    fun decodesAClubSelectionResult() {
        val response =
            ControlCodec.decodeResponse(
                """{"schema_version":2,"request_id":"req-1","ok":true,"result":{"status":"ok","club":"7-iron"}}"""
                    .encodeToByteArray(),
            )

        val club = ControlCodec.decodeClubResult(response)

        assertThat(club.status).isEqualTo("ok")
        assertThat(club.club).isEqualTo(GolfClub.IRON_7)
    }

    @Test
    fun decodesACalibrationResult() {
        val response =
            ControlCodec.decodeResponse(
                """
                {"schema_version":2,"request_id":"req-3","ok":true,"result":
                  {"status":"ok","persistent":true,"measured_mount_tilt_deg":12.3,
                   "enclosure_pitch_deg":null,"configured_iwr_tilt_deg":12.3,
                   "roll_deg":0.4,"azimuth_offset_deg":0.0}}
                """.trimIndent().encodeToByteArray(),
            )

        val result = ControlCodec.decodeCalibrationResult(response)

        assertThat(result.measuredMountTiltDeg).isEqualTo(12.3)
        assertThat(result.enclosurePitchDeg).isEqualTo(null)
    }

    @Test
    fun errorResponseThrowsWithTheServerMessage() {
        val response =
            ControlCodec.decodeResponse(
                """{"schema_version":2,"request_id":"req-1","ok":false,"error":"TI IWR6843 radar is not enabled"}"""
                    .encodeToByteArray(),
            )

        assertFailure { ControlCodec.decodeClubResult(response) }
            .isInstanceOf<ControlDecodeError.ServerError>()
    }

    @Test
    fun aVersionOneResponseIsRejected() {
        val response =
            ControlCodec.decodeResponse(
                """{"schema_version":1,"request_id":"req-1","ok":true,"result":{"status":"ok","club":"driver"}}"""
                    .encodeToByteArray(),
            )

        assertFailure { ControlCodec.decodeClubResult(response) }
            .isInstanceOf<ControlDecodeError.UnsupportedSchema>()
    }

    @Test
    fun bluetoothAcceptsOnlySchemaTwoClubChanged() {
        val v1 = """{"schema_version":1,"type":"club_changed","club":"3-wood"}""".encodeToByteArray()
        val v2 = """{"schema_version":2,"type":"club_changed","club":"driver"}""".encodeToByteArray()

        assertThat(ControlCodec.decodeClubChangedEvent(v2, ControlCodec.V2_SCHEMAS)).isEqualTo(GolfClub.DRIVER)
        assertFailure { ControlCodec.decodeClubChangedEvent(v1, ControlCodec.V2_SCHEMAS) }
            .isInstanceOf<ControlDecodeError.UnsupportedSchema>()
    }

    @Test
    fun theSseStreamAcceptsSchemaOneAndTwoButNotThree() {
        val v1 = """{"schema_version":1,"type":"club_changed","club":"3-wood"}""".encodeToByteArray()
        val v2 = """{"schema_version":2,"type":"club_changed","club":"driver"}""".encodeToByteArray()
        val v3 = """{"schema_version":3,"type":"club_changed","club":"driver"}""".encodeToByteArray()

        assertThat(ControlCodec.decodeClubChangedEvent(v1, ControlCodec.V1_AND_V2_SCHEMAS)).isEqualTo(GolfClub.WOOD_3)
        assertThat(ControlCodec.decodeClubChangedEvent(v2, ControlCodec.V1_AND_V2_SCHEMAS)).isEqualTo(GolfClub.DRIVER)
        assertFailure { ControlCodec.decodeClubChangedEvent(v3, ControlCodec.V1_AND_V2_SCHEMAS) }
            .isInstanceOf<ControlDecodeError.UnsupportedSchema>()
    }
}
