// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.prop
import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
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
 * [PiCameraClient] against the backend's camera routes (server.py:1337-1398). The 404/503 bodies
 * are the exact text `curl` got from `openflight-server --mock` at `2974e5d`
 * ("Camera capture not enabled", `{"error":"Camera replay was not found"}`); the 200 bodies follow
 * the route source.
 */
class PiCameraClientTest {
    private val requests = mutableListOf<Pair<HttpMethod, String>>()

    private fun client(
        status: HttpStatusCode,
        body: ByteArray,
        contentType: String,
    ): PiCameraClient {
        val engine =
            MockEngine { request ->
                requests += request.method to request.url.toString()
                respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, contentType))
            }
        return PiCameraClient(HttpClient(engine) { installOpenFlightDefaults() })
    }

    @Test
    fun aPreviewStillIsTheJpegBody() =
        runTest {
            val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
            val camera = client(HttpStatusCode.OK, jpeg, "image/jpeg")

            val preview = camera.preview("pi.local:8080")

            assertThat(preview).isEqualTo(CameraPreview.Frame(jpeg))
            assertThat(requests).containsExactly(HttpMethod.Get to "http://pi.local:8080/api/camera/preview.jpg")
        }

    @Test
    fun aPiWithoutCameraCaptureAnswers404() =
        runTest {
            val camera = client(HttpStatusCode.NotFound, "Camera capture not enabled".encodeToByteArray(), "text/html")

            assertThat(camera.preview("pi.local:8080")).isEqualTo(CameraPreview.CaptureNotEnabled)
        }

    @Test
    fun aStoppedCameraAnswers503() =
        runTest {
            val camera =
                client(HttpStatusCode.ServiceUnavailable, "Camera not running".encodeToByteArray(), "text/html")

            assertThat(camera.preview("pi.local:8080")).isEqualTo(CameraPreview.CameraNotRunning)
        }

    @Test
    fun anyOtherAnswerKeepsTheServersText() =
        runTest {
            val camera = client(HttpStatusCode.InternalServerError, "Boom".encodeToByteArray(), "text/plain")

            assertThat(camera.preview("pi.local:8080")).isEqualTo(CameraPreview.Unavailable(500, "Boom"))
        }

    @Test
    fun preparingAReplayPostsAndReturnsItsVideo() =
        runTest {
            val body =
                """{"id":"a1b2","frame_count":120,"trigger_frame":40,"playback_fps":30,"duration_seconds":4.0,""" +
                    """"display_mirror_horizontal":true,"video_url":"/api/camera/replays/a1b2/video"}"""
            val camera = client(HttpStatusCode.OK, body.encodeToByteArray(), "application/json")

            val replay = camera.prepareReplay("pi.local:8080", "a1b2")

            assertThat(replay).isEqualTo(
                CameraReplay(
                    id = "a1b2",
                    frameCount = 120,
                    triggerFrame = 40,
                    playbackFps = 30.0,
                    durationSeconds = 4.0,
                    displayMirrorHorizontal = true,
                    videoUrl = "/api/camera/replays/a1b2/video",
                ),
            )
            assertThat(requests).containsExactly(
                HttpMethod.Post to "http://pi.local:8080/api/camera/replays/a1b2/prepare",
            )
            assertThat(camera.videoUrl("pi.local:8080", replay))
                .isEqualTo("http://pi.local:8080/api/camera/replays/a1b2/video")
        }

    @Test
    fun anUnknownReplayFailsWithTheServersError() =
        runTest {
            val camera =
                client(
                    HttpStatusCode.NotFound,
                    """{"error":"Camera replay was not found"}""".encodeToByteArray(),
                    "application/json",
                )

            assertFailure { camera.prepareReplay("pi.local:8080", "abc") }
                .isInstanceOf(OpenFlightHttpError.UnexpectedStatus::class)
                .prop(OpenFlightHttpError.UnexpectedStatus::serverMessage)
                .isEqualTo("Camera replay was not found")
        }

    @Test
    fun aReplayIdThatIsNotAPlainTokenIsNeverSent() =
        runTest {
            val camera = client(HttpStatusCode.OK, ByteArray(0), "application/json")

            assertFailure { camera.prepareReplay("pi.local:8080", "../shutdown") }
                .isInstanceOf(IllegalArgumentException::class)
            assertThat(requests.size).isEqualTo(0)
        }

    @Test
    fun aReplayWithoutAVideoPathHasNoUrl() {
        val camera = PiCameraClient(HttpClient(MockEngine { respond("") }))

        assertThat(camera.videoUrl("pi.local:8080", CameraReplay(id = "x"))).isNull()
        assertThat(camera.videoUrl("pi.local:8080", CameraReplay(id = "x", videoUrl = "http://evil/x"))).isNull()
    }
}
