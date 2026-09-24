// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MjpegParserTest {
    // Fake JPEGs that contain CR/LF and dashes, so a naive line splitter would break them.
    private val frames =
        listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x0D, 0x0A, 0x2D, 0x2D, 0x01, 0xFF.toByte(), 0xD9.toByte()),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte()),
            ByteArray(5_000) { (it % 251).toByte() },
        )

    /** Exactly what server.py `generate_mjpeg()` yields per frame. */
    private fun serverBody(parts: List<ByteArray>): ByteArray =
        parts.fold(ByteArray(0)) { body, jpeg ->
            body + "--frame\r\nContent-Type: image/jpeg\r\n\r\n".encodeToByteArray() + jpeg + "\r\n".encodeToByteArray()
        }

    private fun List<ByteArray>.asLists() = map { it.toList() }

    @Test
    fun extractsEveryFrameThatAnotherBoundaryHasClosed() {
        val parser = MjpegParser("frame")

        val emitted = parser.feed(serverBody(frames))

        // The last part stays buffered: without Content-Length it ends only when the next boundary arrives.
        assertThat(emitted.asLists()).containsExactly(frames[0].toList(), frames[1].toList())
    }

    @Test
    fun emitsTheSameFramesWhateverTheChunking() {
        val body = serverBody(frames + frames[0])
        for (chunkSize in listOf(1, 2, 7, 64, 4_096)) {
            val parser = MjpegParser("frame")
            val emitted = body.toList().chunked(chunkSize).flatMap { parser.feed(it.toByteArray()) }

            assertThat(emitted.asLists(), name = "chunk=$chunkSize").isEqualTo(frames.asLists())
        }
    }

    @Test
    fun usesContentLengthToEmitAFrameWithoutWaitingForTheNextBoundary() {
        val jpeg = frames[0]
        val body =
            "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".encodeToByteArray() + jpeg

        assertThat(MjpegParser("frame").feed(body).asLists()).containsExactly(jpeg.toList())
    }

    @Test
    fun ignoresPreambleBeforeTheFirstBoundary() {
        val body = "garbage\r\n".encodeToByteArray() + serverBody(frames.take(2) + frames[0])

        assertThat(MjpegParser("frame").feed(body).asLists()).containsExactly(frames[0].toList(), frames[1].toList())
    }

    @Test
    fun emitsNothingForAnEmptyOrHeaderOnlyStream() {
        assertThat(MjpegParser("frame").feed(ByteArray(0))).isEmpty()
        assertThat(MjpegParser("frame").feed("--frame\r\nContent-Type: image/jpeg\r\n".encodeToByteArray())).isEmpty()
    }

    @Test
    fun flowOperatorParsesAChunkedStream() =
        runTest {
            val chunks = serverBody(frames + frames[1]).toList().chunked(33).map { it.toByteArray() }

            val emitted = chunks.asFlow().mjpegFrames("frame").toList()

            assertThat(emitted.asLists()).isEqualTo(frames.asLists())
        }

    @Test
    fun readsTheBoundaryFromTheContentType() {
        assertThat(mjpegBoundary("multipart/x-mixed-replace; boundary=frame")).isEqualTo("frame")
        assertThat(mjpegBoundary("multipart/x-mixed-replace;boundary=\"my frame\"")).isEqualTo("my frame")
        assertThat(mjpegBoundary("multipart/x-mixed-replace; boundary=--frame")).isEqualTo("frame")
        assertThat(mjpegBoundary("text/plain")).isNull()
    }
}
