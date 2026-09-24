// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * The Wi-Fi request halves of `ios/OpenFlightTests/PhoneOrientationTests.swift`: the URL, method,
 * headers and body `RadarCalibrationClient`/`ClubSelectionClient` build, checked here against the
 * requests [WifiShotTransport] actually sends. The measurement-math halves live in `core:sensors`.
 */
class WifiRequestShapeTest {
    @Test
    fun calibrationRequestUsesWifiHostAndSnakeCasePayload() =
        runTest {
            val captured = captureRequest(CALIBRATION_RESPONSE) { it.submitCalibration(levelPortraitMeasurement()) }
            val body = captured.jsonBody()

            assertThat(captured.url.toString())
                .isEqualTo("http://raspberrypi.local:8080/api/calibration/iwr6843/orientation")
            assertThat(captured.method).isEqualTo(HttpMethod.Post)
            assertThat(captured.body.contentType?.withoutParameters()).isEqualTo(ContentType.Application.Json)
            assertThat(body.getValue("schema_version").jsonPrimitive.int).isEqualTo(1)
            assertThat(body.getValue("mount_tilt_deg").jsonPrimitive.double).isEqualTo(0.0)
            assertThat(body.getValue("sample_count").jsonPrimitive.int).isEqualTo(120)
            assertThat(body.getValue("device_model").jsonPrimitive.content).isEqualTo("iPhone")
        }

    @Test
    fun calibrationUrlRejectsUnsupportedHost() {
        assertThat(EndpointUrl.build("ftp://pi.local", WifiShotTransport.CALIBRATION_PATH)).isNull()
        assertThat(EndpointUrl.build(" ", WifiShotTransport.CALIBRATION_PATH)).isNull()
    }

    @Test
    fun calibrationWithUnsupportedHostSendsNoRequest() =
        runTest {
            var requested = false
            val engine =
                MockEngine {
                    requested = true
                    respond(content = "")
                }
            val transport = WifiShotTransport("ftp://pi.local", HttpClient(engine) { installOpenFlightDefaults() })

            val error = runCatching { transport.submitCalibration(levelPortraitMeasurement()) }.exceptionOrNull()

            assertThat(error).isEqualTo(OpenFlightHttpError.InvalidHost("ftp://pi.local"))
            assertThat(requested).isFalse()
        }

    @Test
    fun wifiClubRequestUsesClubEndpoint() =
        runTest {
            val captured = captureRequest(CLUB_RESPONSE) { it.setClub(GolfClub.PITCHING_WEDGE) }

            assertThat(captured.url.toString()).isEqualTo("http://raspberrypi.local:8080/api/club")
            assertThat(captured.method).isEqualTo(HttpMethod.Post)
            assertThat(
                captured
                    .jsonBody()
                    .getValue("club")
                    .jsonPrimitive.content,
            ).isEqualTo("pw")
        }

    @Test
    fun wifiCurrentClubRequestReadsClubEndpoint() =
        runTest {
            val captured = captureRequest(CLUB_RESPONSE) { it.currentClub() }

            assertThat(captured.url.toString()).isEqualTo("http://raspberrypi.local:8080/api/club")
            assertThat(captured.method).isEqualTo(HttpMethod.Get)
            assertThat(captured.body.toByteArray().toList()).isEmpty()
        }

    private suspend fun captureRequest(
        responseJson: String,
        call: suspend (WifiShotTransport) -> Unit,
    ): HttpRequestData {
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                respond(
                    content = responseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        call(WifiShotTransport("raspberrypi.local", HttpClient(engine) { installOpenFlightDefaults() }))
        return checkNotNull(captured)
    }

    private suspend fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    private companion object {
        const val CLUB_RESPONSE = """{"status":"ok","club":"pw"}"""
        const val CALIBRATION_RESPONSE =
            """{"status":"applied","persistent":true,"measured_mount_tilt_deg":0.0,""" +
                """"configured_iwr_tilt_deg":0.0,"roll_deg":0.0,"azimuth_offset_deg":0.0}"""

        /**
         * What the reference's calculator produces from 120 samples of `(0, -1, 0)` (a portrait
         * phone against a vertical face) at `Date(timeIntervalSince1970: 0)` on an "iPhone".
         */
        fun levelPortraitMeasurement() =
            PhoneOrientationMeasurement(
                mountTiltDeg = 0.0,
                rollDeg = 0.0,
                gravityXG = 0.0,
                gravityYG = -1.0,
                gravityZG = 0.0,
                tiltStddevDeg = 0.0,
                rollStddevDeg = 0.0,
                sampleCount = 120,
                measuredAt = "1970-01-01T00:00:00Z",
                deviceModel = "iPhone",
            )
    }
}
