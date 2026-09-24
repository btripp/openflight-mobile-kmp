// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.GolfClub
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * `setClub`/`currentClub`/`submitCalibration` against `/api/club` and
 * `/api/calibration/iwr6843/orientation`, ported from `ClubSelectionClient`/`RadarCalibrationClient`
 * (plan Step 4, task 4).
 */
class WifiControlRequestsTest {
    @Test
    fun setClubPostsAndDecodesTheResult() =
        runTest {
            var capturedMethod: HttpMethod? = null
            val engine =
                MockEngine { request ->
                    capturedMethod = request.method
                    respond(
                        content = """{"status":"ok","club":"7-iron"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val transport = controlTransport(engine)

            val result = transport.setClub(GolfClub.IRON_7)

            assertThat(result).isEqualTo(ClubSelection(status = "ok", club = GolfClub.IRON_7))
            assertThat(capturedMethod).isEqualTo(HttpMethod.Post)
        }

    @Test
    fun currentClubGetsAndDecodesTheResult() =
        runTest {
            var capturedMethod: HttpMethod? = null
            val engine =
                MockEngine { request ->
                    capturedMethod = request.method
                    respond(
                        content = """{"status":"ok","club":"driver"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val transport = controlTransport(engine)

            val result = transport.currentClub()

            assertThat(result).isEqualTo(ClubSelection(status = "ok", club = GolfClub.DRIVER))
            assertThat(capturedMethod).isEqualTo(HttpMethod.Get)
        }

    @Test
    fun submitCalibrationPostsAndDecodesTheResult() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {"status":"applied","persistent":true,"measured_mount_tilt_deg":12.3,
                            "enclosure_pitch_deg":1.1,"configured_iwr_tilt_deg":12.0,"roll_deg":0.4,
                            "azimuth_offset_deg":0.0}
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val transport = controlTransport(engine)

            val result = transport.submitCalibration(sampleMeasurement())

            assertThat(result).isEqualTo(
                CalibrationResult(
                    status = "applied",
                    persistent = true,
                    measuredMountTiltDeg = 12.3,
                    enclosurePitchDeg = 1.1,
                    configuredIwrTiltDeg = 12.0,
                    rollDeg = 0.4,
                    azimuthOffsetDeg = 0.0,
                ),
            )
        }

    @Test
    fun aNonTwoHundredResponseThrowsWithTheServersErrorMessage() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":"TI IWR6843 radar is not enabled"}""",
                        status = HttpStatusCode.Conflict,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val transport = controlTransport(engine)

            val error =
                try {
                    transport.submitCalibration(sampleMeasurement())
                    null
                } catch (e: OpenFlightHttpError.UnexpectedStatus) {
                    e
                }

            assertThat(error?.statusCode).isEqualTo(HttpStatusCode.Conflict.value)
            assertThat(error?.serverMessage).isEqualTo("TI IWR6843 radar is not enabled")
        }

    @Test
    fun anInvalidHostThrowsWithoutMakingARequest() =
        runTest {
            var requested = false
            val engine =
                MockEngine {
                    requested = true
                    respond(content = "")
                }
            val transport = controlTransport(engine, host = " ")

            val error =
                try {
                    transport.currentClub()
                    null
                } catch (e: OpenFlightHttpError.InvalidHost) {
                    e
                }

            assertThat(error?.host).isEqualTo(" ")
            assertThat(requested).isFalse()
        }
}

private fun controlTransport(
    engine: MockEngine,
    host: String = DEFAULT_TEST_HOST,
): WifiShotTransport {
    val client = HttpClient(engine) { installOpenFlightDefaults() }
    return WifiShotTransport(host = host, httpClient = client)
}
