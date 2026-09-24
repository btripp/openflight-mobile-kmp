// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import assertk.assertions.prop
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class KtorPiCameraSourceTest {
    private val requestedUrls = mutableListOf<String>()

    private fun source(
        status: HttpStatusCode,
        body: ByteArray,
        contentType: String,
    ) = KtorPiCameraSource(
        HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
            },
        ) { install(HttpTimeout) },
    )

    @Test
    fun streamsTheJpegFramesOfTheMultipartBody() =
        runTest {
            val jpegs = listOf(byteArrayOf(-1, -40, 1, -1, -39), byteArrayOf(-1, -40, 2, -1, -39))
            val body =
                (jpegs + jpegs[0]).fold(ByteArray(0)) { acc, jpeg ->
                    acc + "--frame\r\nContent-Type: image/jpeg\r\n\r\n".encodeToByteArray() + jpeg +
                        "\r\n".encodeToByteArray()
                }

            val frames =
                source(HttpStatusCode.OK, body, "multipart/x-mixed-replace; boundary=frame")
                    .frames("pi.local:8080")
                    .toList()

            assertThat(frames.map { it.toList() }).containsExactly(jpegs[0].toList(), jpegs[1].toList())
            assertThat(requestedUrls).containsExactly("http://pi.local:8080/camera/stream")
        }

    @Test
    fun aServerErrorIsReportedWithTheServersMessage() =
        runTest {
            val camera =
                source(HttpStatusCode.ServiceUnavailable, "Camera not available".encodeToByteArray(), "text/html")

            val failure = assertFailure { camera.frames("pi.local:8080").toList() }
            failure.messageContains("Camera not available")
            failure
                .isInstanceOf(
                    PiCameraUnavailableException::class,
                ).prop(PiCameraUnavailableException::status)
                .isEqualTo(503)
        }
}
