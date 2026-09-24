// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The Engine.IO v4 open-packet body (`0{...}`). */
@Serializable
data class EngineIoHandshake(
    val sid: String,
    val upgrades: List<String> = emptyList(),
    @SerialName("pingInterval") val pingInterval: Long = DEFAULT_PING_INTERVAL_MS,
    @SerialName("pingTimeout") val pingTimeout: Long = DEFAULT_PING_TIMEOUT_MS,
    @SerialName("maxPayload") val maxPayload: Long = DEFAULT_MAX_PAYLOAD,
) {
    companion object {
        // python-engineio's defaults, used only if the server leaves a field out.
        const val DEFAULT_PING_INTERVAL_MS: Long = 25_000
        const val DEFAULT_PING_TIMEOUT_MS: Long = 20_000
        const val DEFAULT_MAX_PAYLOAD: Long = 1_000_000
    }
}

/** One Engine.IO v4 packet. Text-only: the OpenFlight server never sends binary attachments. */
sealed interface EngineIoPacket {
    data class Open(
        val handshake: EngineIoHandshake,
    ) : EngineIoPacket

    data object Close : EngineIoPacket

    data class Ping(
        val data: String = "",
    ) : EngineIoPacket

    data class Pong(
        val data: String = "",
    ) : EngineIoPacket

    /** A message: for Socket.IO, [data] is one encoded [SocketIoPacket]. */
    data class Message(
        val data: String,
    ) : EngineIoPacket

    data object Upgrade : EngineIoPacket

    data object Noop : EngineIoPacket
}

/**
 * Engine.IO v4 text codec. Over a WebSocket each frame is one packet; over long-polling a body is
 * a payload of packets separated by the record separator `\u001e`.
 */
object EngineIoCodec {
    const val RECORD_SEPARATOR: Char = '\u001e'

    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes one packet, or returns `null` for an empty, unknown or malformed one. */
    fun decodePacket(text: String): EngineIoPacket? {
        if (text.isEmpty()) return null
        val data = text.substring(1)
        return when (text[0]) {
            '0' -> decodeOpen(data)
            '1' -> EngineIoPacket.Close
            '2' -> EngineIoPacket.Ping(data)
            '3' -> EngineIoPacket.Pong(data)
            '4' -> EngineIoPacket.Message(data)
            '5' -> EngineIoPacket.Upgrade
            '6' -> EngineIoPacket.Noop
            else -> null
        }
    }

    fun encodePacket(packet: EngineIoPacket): String =
        when (packet) {
            is EngineIoPacket.Open -> "0" + json.encodeToString(EngineIoHandshake.serializer(), packet.handshake)
            EngineIoPacket.Close -> "1"
            is EngineIoPacket.Ping -> "2" + packet.data
            is EngineIoPacket.Pong -> "3" + packet.data
            is EngineIoPacket.Message -> "4" + packet.data
            EngineIoPacket.Upgrade -> "5"
            EngineIoPacket.Noop -> "6"
        }

    /** Decodes a long-polling payload, skipping packets that don't decode. */
    fun decodePayload(payload: String): List<EngineIoPacket> =
        payload.split(RECORD_SEPARATOR).mapNotNull(::decodePacket)

    fun encodePayload(packets: List<EngineIoPacket>): String =
        packets.joinToString(RECORD_SEPARATOR.toString(), transform = ::encodePacket)

    private fun decodeOpen(data: String): EngineIoPacket.Open? =
        try {
            EngineIoPacket.Open(json.decodeFromString(EngineIoHandshake.serializer(), data))
        } catch (_: IllegalArgumentException) {
            // kotlinx SerializationException is an IllegalArgumentException.
            null
        }
}
