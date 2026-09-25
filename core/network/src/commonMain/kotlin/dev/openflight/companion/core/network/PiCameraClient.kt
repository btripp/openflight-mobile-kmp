// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * The Pi's camera over HTTP (backend `2974e5d`, server.py:1337-1445), replacing the removed MJPEG
 * stream and camera toggles:
 *
 * - [preview]: `GET /api/camera/preview.jpg`, one still per call; the caller polls it.
 * - [prepareReplay]: `POST /api/camera/replays/<id>/prepare` builds (or reuses) a shot's MP4 and
 *   answers with its metadata plus `video_url`; [videoUrl] turns that into an absolute URL a
 *   native player can stream (`GET …/video`, with HTTP Range support).
 */
class PiCameraClient(
    private val httpClient: HttpClient,
) {
    /**
     * One preview still. The two documented refusals map to [CameraPreview.CaptureNotEnabled]
     * (404) and [CameraPreview.CameraNotRunning] (503); anything else non-200 is
     * [CameraPreview.Unavailable].
     *
     * @throws OpenFlightHttpError.InvalidHost for an unusable host; transport failures propagate.
     */
    suspend fun preview(host: String): CameraPreview {
        val url = EndpointUrl.build(host, PREVIEW_PATH) ?: throw OpenFlightHttpError.InvalidHost(host)
        val response = httpClient.get(url) { timeout { requestTimeoutMillis = PREVIEW_TIMEOUT_MILLIS } }
        return when (response.status) {
            HttpStatusCode.OK -> {
                CameraPreview.Frame(response.bodyAsBytes())
            }

            HttpStatusCode.NotFound -> {
                CameraPreview.CaptureNotEnabled
            }

            HttpStatusCode.ServiceUnavailable -> {
                CameraPreview.CameraNotRunning
            }

            else -> {
                val text = response.bodyAsText().ifBlank { response.status.description }
                CameraPreview.Unavailable(response.status.value, text)
            }
        }
    }

    /**
     * Prepares shot replay [replayId] (`ShotDetail.cameraReplay.id`). Encoding an uncached replay
     * can take a while on a Pi, hence the long timeout.
     *
     * @throws IllegalArgumentException for an id that isn't a plain token (never put in a path).
     * @throws OpenFlightHttpError.UnexpectedStatus with the server's `error` text (404 unknown
     *   replay, 503 encoding failed, ...).
     */
    suspend fun prepareReplay(
        host: String,
        replayId: String,
    ): CameraReplay {
        require(REPLAY_ID.matches(replayId)) { "Not a camera replay id: $replayId" }
        val url =
            EndpointUrl.build(host, "$REPLAYS_PATH/$replayId/prepare") ?: throw OpenFlightHttpError.InvalidHost(host)
        val response = httpClient.post(url) { timeout { requestTimeoutMillis = PREPARE_TIMEOUT_MILLIS } }
        if (response.status != HttpStatusCode.OK) {
            val message = runCatching { response.body<CameraErrorBody>().error }.getOrNull()
            throw OpenFlightHttpError.UnexpectedStatus(response.status.value, message)
        }
        return response.body()
    }

    /** The absolute URL of a prepared replay's MP4 on [host], or `null` without a `video_url`. */
    fun videoUrl(
        host: String,
        replay: CameraReplay,
    ): String? = replay.videoUrl?.takeIf { it.startsWith("/") }?.let { EndpointUrl.build(host, it) }

    companion object {
        const val PREVIEW_PATH = "/api/camera/preview.jpg"
        const val REPLAYS_PATH = "/api/camera/replays"
        private const val PREVIEW_TIMEOUT_MILLIS = 5_000L
        private const val PREPARE_TIMEOUT_MILLIS = 60_000L
        private val REPLAY_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

@Serializable
private data class CameraErrorBody(
    val error: String? = null,
)
