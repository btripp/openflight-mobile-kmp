// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Incremental parser for a `multipart/x-mixed-replace` (MJPEG) body such as the Pi's
 * `GET /camera/stream`, which writes `--frame\r\nContent-Type: image/jpeg\r\n\r\n<jpeg>\r\n`
 * per frame (server.py `generate_mjpeg`).
 *
 * A part with a `Content-Length` header is emitted as soon as its bytes arrive. A part without one
 * (the Pi's case) ends at the next `\r\n--<boundary>`, so it is emitted when the next part starts.
 * The payload bytes are returned as-is; decoding the JPEG is up to each platform.
 */
class MjpegParser(
    boundary: String,
) {
    private val delimiter = "--$boundary".encodeToByteArray()
    private val bodyEnd = "\r\n--$boundary".encodeToByteArray()
    private var buffer = ByteArray(0)

    /** Appends [chunk] and returns every part it completed, oldest first. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer += chunk
        val parts = mutableListOf<ByteArray>()
        while (true) {
            val part = nextPart() ?: break
            parts += part
        }
        return parts
    }

    @Suppress("ReturnCount") // Each early return means "need more bytes".
    private fun nextPart(): ByteArray? {
        val start = buffer.indexOf(delimiter, 0)
        if (start < 0) {
            // Keep a tail that might be the start of a delimiter split across chunks.
            if (buffer.size > delimiter.size) buffer = buffer.copyOfRange(buffer.size - delimiter.size, buffer.size)
            return null
        }
        val headersEnd = buffer.indexOf(HEADERS_END, start + delimiter.size)
        if (headersEnd < 0) return null
        val bodyStart = headersEnd + HEADERS_END.size
        val contentLength = contentLength(buffer.decodeToString(start + delimiter.size, headersEnd))

        val bodyEndIndex: Int
        val consumedTo: Int
        if (contentLength != null) {
            bodyEndIndex = bodyStart + contentLength
            if (bodyEndIndex > buffer.size) return null
            consumedTo = bodyEndIndex
        } else {
            bodyEndIndex = buffer.indexOf(bodyEnd, bodyStart)
            if (bodyEndIndex < 0) return null
            consumedTo = bodyEndIndex + CRLF_LENGTH
        }
        val part = buffer.copyOfRange(bodyStart, bodyEndIndex)
        buffer = buffer.copyOfRange(consumedTo, buffer.size)
        return part
    }

    private fun contentLength(headers: String): Int? =
        headers
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }

    private companion object {
        val HEADERS_END = "\r\n\r\n".encodeToByteArray()
        const val CRLF_LENGTH = 2
    }
}

/** Parses this chunked MJPEG body into its JPEG frames (see [MjpegParser]). */
fun Flow<ByteArray>.mjpegFrames(boundary: String): Flow<ByteArray> =
    flow {
        val parser = MjpegParser(boundary)
        collect { chunk -> parser.feed(chunk).forEach { emit(it) } }
    }

/** The `boundary` parameter of a multipart `Content-Type`, without quotes or a leading `--`. */
fun mjpegBoundary(contentType: String): String? =
    contentType
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removePrefix("--")
        ?.takeIf { it.isNotEmpty() }

private fun ByteArray.indexOf(
    needle: ByteArray,
    from: Int,
): Int {
    val last = size - needle.size
    var index = from.coerceAtLeast(0)
    while (index <= last && !matchesAt(needle, index)) index++
    return if (index <= last) index else -1
}

private fun ByteArray.matchesAt(
    needle: ByteArray,
    offset: Int,
): Boolean {
    for (i in needle.indices) if (this[offset + i] != needle[i]) return false
    return true
}
