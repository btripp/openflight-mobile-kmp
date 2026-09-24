// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.socketio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class EngineIoCodecTest {
    @Test
    fun decodesTheOpenPacketTheMockServerSends() {
        // Captured from `openflight-server --mock` (python-engineio 4.14) over a direct WebSocket.
        val packet =
            EngineIoCodec.decodePacket(
                """0{"sid":"Yyw0B5BW1obTLoMJAAAC","upgrades":[],"pingTimeout":20000,""" +
                    """"pingInterval":25000,"maxPayload":1000000}""",
            )

        assertThat(packet).isEqualTo(
            EngineIoPacket.Open(
                EngineIoHandshake(
                    sid = "Yyw0B5BW1obTLoMJAAAC",
                    upgrades = emptyList(),
                    pingInterval = 25_000,
                    pingTimeout = 20_000,
                    maxPayload = 1_000_000,
                ),
            ),
        )
    }

    @Test
    fun decodesEverySingleCharacterPacketType() {
        assertThat(EngineIoCodec.decodePacket("1")).isEqualTo(EngineIoPacket.Close)
        assertThat(EngineIoCodec.decodePacket("2")).isEqualTo(EngineIoPacket.Ping(""))
        assertThat(EngineIoCodec.decodePacket("3probe")).isEqualTo(EngineIoPacket.Pong("probe"))
        assertThat(EngineIoCodec.decodePacket("5")).isEqualTo(EngineIoPacket.Upgrade)
        assertThat(EngineIoCodec.decodePacket("6")).isEqualTo(EngineIoPacket.Noop)
        assertThat(
            EngineIoCodec.decodePacket("""42["shot",{}]"""),
        ).isEqualTo(EngineIoPacket.Message("""2["shot",{}]"""))
    }

    @Test
    fun rejectsEmptyAndUnknownPackets() {
        assertThat(EngineIoCodec.decodePacket("")).isNull()
        assertThat(EngineIoCodec.decodePacket("9")).isNull()
        assertThat(EngineIoCodec.decodePacket("0not-json")).isNull()
    }

    @Test
    fun encodesPacketsAsTypeDigitPlusData() {
        assertThat(EngineIoCodec.encodePacket(EngineIoPacket.Pong(""))).isEqualTo("3")
        assertThat(EngineIoCodec.encodePacket(EngineIoPacket.Ping("probe"))).isEqualTo("2probe")
        assertThat(EngineIoCodec.encodePacket(EngineIoPacket.Upgrade)).isEqualTo("5")
        assertThat(EngineIoCodec.encodePacket(EngineIoPacket.Close)).isEqualTo("1")
        assertThat(EngineIoCodec.encodePacket(EngineIoPacket.Message("0"))).isEqualTo("40")
    }

    @Test
    fun splitsALongPollingPayloadOnTheRecordSeparator() {
        val payload = "2\u001e42[\"club_changed\",{\"club\":\"driver\"}]\u001e40{\"sid\":\"abc\"}"

        assertThat(EngineIoCodec.decodePayload(payload)).containsExactly(
            EngineIoPacket.Ping(""),
            EngineIoPacket.Message("2[\"club_changed\",{\"club\":\"driver\"}]"),
            EngineIoPacket.Message("0{\"sid\":\"abc\"}"),
        )
    }

    @Test
    fun joinsPacketsIntoOneLongPollingPayload() {
        val payload = EngineIoCodec.encodePayload(listOf(EngineIoPacket.Pong(""), EngineIoPacket.Message("0")))

        assertThat(payload).isEqualTo("3\u001e40")
    }

    @Test
    fun dropsUndecodablePacketsFromAPayloadButKeepsTheRest() {
        assertThat(EngineIoCodec.decodePayload("2\u001e\u001e9x\u001e6")).containsExactly(
            EngineIoPacket.Ping(""),
            EngineIoPacket.Noop,
        )
    }
}
