// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class SocketIoCodecTest {
    @Test
    fun decodesAConnectAckOnTheDefaultNamespace() {
        assertThat(SocketIoCodec.decode("""0{"sid":"n1hTinl8D9RUgCmEAAAD"}""")).isEqualTo(
            SocketIoPacket.Connect(namespace = "/", data = buildJsonObject { put("sid", "n1hTinl8D9RUgCmEAAAD") }),
        )
    }

    @Test
    fun decodesAnEventWithAnObjectPayload() {
        val packet = SocketIoCodec.decode("""2["club_changed",{"club":"driver"}]""")

        assertThat(packet).isEqualTo(
            SocketIoPacket.Event(
                namespace = "/",
                name = "club_changed",
                args = listOf(buildJsonObject { put("club", "driver") }),
            ),
        )
    }

    @Test
    fun decodesAnEventWithNoPayload() {
        assertThat(SocketIoCodec.decode("""2["session_cleared"]""")).isEqualTo(
            SocketIoPacket.Event(namespace = "/", name = "session_cleared", args = emptyList()),
        )
    }

    @Test
    fun decodesNamespaceAndAckId() {
        assertThat(SocketIoCodec.decode("""2/admin,17["ping",1]""")).isEqualTo(
            SocketIoPacket.Event(namespace = "/admin", name = "ping", args = listOf(JsonPrimitive(1)), ackId = 17),
        )
        assertThat(SocketIoCodec.decode("""312["ok"]""")).isEqualTo(
            SocketIoPacket.Ack(namespace = "/", id = 12, args = listOf(JsonPrimitive("ok"))),
        )
    }

    @Test
    fun decodesDisconnectAndConnectError() {
        assertThat(SocketIoCodec.decode("1")).isEqualTo(SocketIoPacket.Disconnect(namespace = "/"))
        assertThat(SocketIoCodec.decode("""4{"message":"Not authorized"}""")).isEqualTo(
            SocketIoPacket.ConnectError(namespace = "/", message = "Not authorized"),
        )
    }

    @Test
    fun rejectsMalformedPackets() {
        assertThat(SocketIoCodec.decode("")).isNull()
        assertThat(SocketIoCodec.decode("2not-json")).isNull()
        assertThat(SocketIoCodec.decode("""2{"not":"an array"}""")).isNull()
        assertThat(SocketIoCodec.decode("2[42]")).isNull()
        assertThat(SocketIoCodec.decode("7")).isNull()
    }

    @Test
    fun encodesConnectForTheDefaultNamespaceAsTheWebClientDoes() {
        assertThat(SocketIoCodec.encode(SocketIoPacket.Connect(namespace = "/"))).isEqualTo("0")
        assertThat(SocketIoCodec.encode(SocketIoPacket.Disconnect(namespace = "/"))).isEqualTo("1")
    }

    @Test
    fun encodesEventsWithAndWithoutData() {
        assertThat(
            SocketIoCodec.encode(SocketIoPacket.Event(name = "simulate_shot")),
        ).isEqualTo("""2["simulate_shot"]""")
        assertThat(
            SocketIoCodec.encode(
                SocketIoPacket.Event(
                    name = "delete_shot",
                    args = listOf(buildJsonObject { put("timestamp", "2026-09-24T15:38:33.264795") }),
                ),
            ),
        ).isEqualTo("""2["delete_shot",{"timestamp":"2026-09-24T15:38:33.264795"}]""")
    }

    @Test
    fun encodesNamespaceAndAckId() {
        assertThat(
            SocketIoCodec.encode(SocketIoPacket.Event(namespace = "/admin", name = "x", ackId = 3)),
        ).isEqualTo("""2/admin,3["x"]""")
    }
}
