// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One Socket.IO v5 packet (the body of an Engine.IO [EngineIoPacket.Message]). */
sealed interface SocketIoPacket {
    val namespace: String

    data class Connect(
        override val namespace: String = DEFAULT_NAMESPACE,
        val data: JsonObject? = null,
    ) : SocketIoPacket

    data class Disconnect(
        override val namespace: String = DEFAULT_NAMESPACE,
    ) : SocketIoPacket

    data class Event(
        override val namespace: String = DEFAULT_NAMESPACE,
        val name: String,
        val args: List<JsonElement> = emptyList(),
        val ackId: Int? = null,
    ) : SocketIoPacket

    data class Ack(
        override val namespace: String = DEFAULT_NAMESPACE,
        val id: Int,
        val args: List<JsonElement> = emptyList(),
    ) : SocketIoPacket

    data class ConnectError(
        override val namespace: String = DEFAULT_NAMESPACE,
        val message: String?,
    ) : SocketIoPacket

    companion object {
        const val DEFAULT_NAMESPACE: String = "/"
    }
}

/**
 * Socket.IO v5 text codec: `<type>[<namespace>,][<ackId>][<json>]`, where the namespace is
 * written only when it isn't `/`. Binary packets (types 5 and 6) are not supported; the
 * OpenFlight server never sends them.
 */
object SocketIoCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes one packet, or returns `null` for an empty, unknown or malformed one. */
    @Suppress("ReturnCount") // Two guard clauses (empty text, malformed JSON) before the dispatch.
    fun decode(text: String): SocketIoPacket? {
        if (text.isEmpty()) return null
        val header = parseHeader(text)
        val body = text.substring(header.bodyStart)
        val payload = if (body.isEmpty()) null else (parseJson(body) ?: return null)
        val namespace = header.namespace
        return when (text[0]) {
            '0' -> SocketIoPacket.Connect(namespace, payload as? JsonObject)
            '1' -> SocketIoPacket.Disconnect(namespace)
            '2' -> decodeEvent(namespace, payload, header.ackId)
            '3' -> header.ackId?.let { SocketIoPacket.Ack(namespace, it, (payload as? JsonArray).orEmpty()) }
            '4' -> SocketIoPacket.ConnectError(namespace, connectErrorMessage(payload))
            else -> null
        }
    }

    fun encode(packet: SocketIoPacket): String {
        val builder = StringBuilder()
        val type =
            when (packet) {
                is SocketIoPacket.Connect -> '0'
                is SocketIoPacket.Disconnect -> '1'
                is SocketIoPacket.Event -> '2'
                is SocketIoPacket.Ack -> '3'
                is SocketIoPacket.ConnectError -> '4'
            }
        builder.append(type)
        if (packet.namespace != SocketIoPacket.DEFAULT_NAMESPACE) builder.append(packet.namespace).append(',')
        when (packet) {
            is SocketIoPacket.Connect -> {
                packet.data?.let { builder.append(it.toString()) }
            }

            is SocketIoPacket.Disconnect -> {
                Unit
            }

            is SocketIoPacket.Event -> {
                packet.ackId?.let(builder::append)
                builder.append(JsonArray(listOf(JsonPrimitive(packet.name)) + packet.args).toString())
            }

            is SocketIoPacket.Ack -> {
                builder.append(packet.id).append(JsonArray(packet.args).toString())
            }

            is SocketIoPacket.ConnectError -> {
                packet.message?.let {
                    builder.append(
                        JsonObject(
                            mapOf("message" to JsonPrimitive(it)),
                        ),
                    )
                }
            }
        }
        return builder.toString()
    }

    /** `[<namespace>,][<ackId>]` after the type character. */
    private fun parseHeader(text: String): Header {
        var index = 1
        var namespace = SocketIoPacket.DEFAULT_NAMESPACE
        if (index < text.length && text[index] == '/') {
            val comma = text.indexOf(',', index).takeIf { it >= 0 } ?: text.length
            namespace = text.substring(index, comma)
            index = (comma + 1).coerceAtMost(text.length)
        }
        val idStart = index
        while (index < text.length && text[index].isDigit()) index++
        val ackId = if (index > idStart) text.substring(idStart, index).toIntOrNull() else null
        return Header(namespace, ackId, index)
    }

    private fun decodeEvent(
        namespace: String,
        payload: JsonElement?,
        ackId: Int?,
    ): SocketIoPacket.Event? {
        val array = payload as? JsonArray
        val name = (array?.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (array == null || name == null) null else SocketIoPacket.Event(namespace, name, array.drop(1), ackId)
    }

    private class Header(
        val namespace: String,
        val ackId: Int?,
        val bodyStart: Int,
    )

    private fun connectErrorMessage(payload: JsonElement?): String? =
        when (payload) {
            is JsonObject -> payload["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> payload.contentOrNull
            else -> null
        }

    private fun parseJson(body: String): JsonElement? =
        try {
            json.parseToJsonElement(body)
        } catch (_: IllegalArgumentException) {
            // kotlinx SerializationException is an IllegalArgumentException.
            null
        }
}
