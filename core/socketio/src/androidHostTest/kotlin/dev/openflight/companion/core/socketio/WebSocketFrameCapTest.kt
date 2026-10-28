// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertThat
import assertk.assertions.isTrue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Issue #166: the Android socket drops a frame over [MAX_FRAME_BYTES] instead of buffering it. */
class WebSocketFrameCapTest {
    @Test
    fun aFrameOverTheCapClosesTheSocketWithoutBufferingIt() =
        runTest {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val _ = thread(isDaemon = true) { serveOversizedFrame(server) }
                val transport = KtorWebSocketTransport(HttpClient(MockEngine { respond("") }))

                // Real sockets: wait on the wall clock, not runTest's virtual one.
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        val connection = transport.open("ws://127.0.0.1:${server.localPort}/socket.io/")
                        // The server waits for this, so the oversized header never shares a read with the 101.
                        connection.send("2probe")
                        // The frame's body never comes: an uncapped client would wait for it forever.
                        assertThat(connection.incoming.receiveCatching().isClosed).isTrue()
                    }
                }
            }
        }

    /** Answers the upgrade and, once the client sends a frame, announces (never sends) one 2 MB text frame. */
    private fun serveOversizedFrame(server: ServerSocket) {
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val key =
                generateSequence { input.readHeaderLine() }
                    .takeWhile { it.isNotEmpty() }
                    .toList() // read every header, so none is mistaken for a frame later
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    .substringAfter(':')
                    .trim()
            val accept =
                Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()),
                )
            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n",
            )
            out.flush()
            input.skipClientFrame()
            out.write(byteArrayOf(0x81.toByte(), 127))
            out.writeLong(2 * MAX_FRAME_BYTES)
            out.flush()
            // Never send the body; hold the socket open until the client hangs up.
            try {
                input.readAllBytes()
            } catch (_: IOException) {
                // The client reset the connection: done.
            }
        }
    }

    /** One CRLF-terminated header line, read byte by byte so no frame bytes are over-read. */
    private fun java.io.InputStream.readHeaderLine(): String =
        buildString {
            while (true) {
                val byte = read()
                if (byte < 0 || byte == '\n'.code) break
                if (byte != '\r'.code) append(byte.toChar())
            }
        }

    /** Skips one short masked client frame (header, 4-byte mask, payload under 126 bytes). */
    private fun java.io.InputStream.skipClientFrame() {
        read()
        readNBytes(MASK_BYTES + (read() and 0x7F))
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC11B65"
        const val MASK_BYTES = 4
    }
}
