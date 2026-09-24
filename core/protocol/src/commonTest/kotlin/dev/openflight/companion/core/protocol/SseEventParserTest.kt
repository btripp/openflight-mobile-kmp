// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class SseEventParserTest {
    @Test
    fun byteStreamPreservesBlankLineAndDispatchesEvent() {
        val parser = SseByteStreamParser()
        val wireEvent = "event: shot\ndata: {\"schema_version\":1}\n\n"

        val events = wireEvent.encodeToByteArray().toList().mapNotNull { parser.append(it) }

        assertThat(events).containsExactly(SseEvent(name = "shot", data = "{\"schema_version\":1}"))
    }

    @Test
    fun byteStreamHandlesCrlfAndConsecutiveEvents() {
        val parser = SseByteStreamParser()
        val stream = "data: first\r\n\r\ndata: second\r\n\r\n"

        val events = stream.encodeToByteArray().toList().mapNotNull { parser.append(it) }

        assertThat(events).containsExactly(
            SseEvent(name = null, data = "first"),
            SseEvent(name = null, data = "second"),
        )
    }

    @Test
    fun byteStreamResetDiscardsPartialLineAndEvent() {
        val parser = SseByteStreamParser()
        for (byte in "event: shot\ndata: partial".encodeToByteArray()) {
            assertThat(parser.append(byte)).isNull()
        }

        parser.reset()
        val events = "data: replacement\n\n".encodeToByteArray().toList().mapNotNull { parser.append(it) }

        assertThat(events).containsExactly(SseEvent(name = null, data = "replacement"))
    }

    @Test
    fun blankLineDispatchesNamedEvent() {
        val parser = SseEventParser()

        assertThat(parser.append("event: shot")).isNull()
        assertThat(parser.append("data: {\"schema_version\":1}")).isNull()
        val event = parser.append("")

        assertThat(event).isEqualTo(SseEvent(name = "shot", data = "{\"schema_version\":1}"))
    }

    @Test
    fun heartbeatCommentsAreIgnored() {
        val parser = SseEventParser()

        assertThat(parser.append(": ping")).isNull()
        assertThat(parser.append("")).isNull()
    }

    @Test
    fun blankLineWithoutDataDispatchesNothing() {
        val parser = SseEventParser()

        assertThat(parser.append("event: shot")).isNull()
        assertThat(parser.append("")).isNull()
    }

    @Test
    fun multipleDataLinesAreJoinedWithNewlines() {
        val parser = SseEventParser()

        assertThat(parser.append("data: first")).isNull()
        assertThat(parser.append("data: second")).isNull()

        assertThat(parser.append("")?.data).isEqualTo("first\nsecond")
    }

    @Test
    fun carriageReturnsAreStripped() {
        val parser = SseEventParser()

        assertThat(parser.append("event: shot\r")).isNull()
        assertThat(parser.append("data: payload\r")).isNull()

        assertThat(parser.append("\r")).isEqualTo(SseEvent(name = "shot", data = "payload"))
    }

    @Test
    fun onlyOneSpaceAfterTheColonIsDropped() {
        val parser = SseEventParser()

        assertThat(parser.append("data:  padded")).isNull()

        assertThat(parser.append("")?.data).isEqualTo(" padded")
    }

    @Test
    fun valueWithoutSpaceAfterColonIsKept() {
        val parser = SseEventParser()

        assertThat(parser.append("data:{\"a\":1}")).isNull()

        assertThat(parser.append("")?.data).isEqualTo("{\"a\":1}")
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val parser = SseEventParser()

        assertThat(parser.append("id: 7")).isNull()
        assertThat(parser.append("retry: 3000")).isNull()
        assertThat(parser.append("data: payload")).isNull()

        assertThat(parser.append("")).isEqualTo(SseEvent(name = null, data = "payload"))
    }

    @Test
    fun consecutiveEventsDoNotLeakState() {
        val parser = SseEventParser()

        parser.append("event: shot")
        parser.append("data: first")
        parser.append("")

        assertThat(parser.append("data: second")).isNull()
        assertThat(parser.append("")).isEqualTo(SseEvent(name = null, data = "second"))
    }

    @Test
    fun resetDiscardsPartialEvent() {
        val parser = SseEventParser()

        parser.append("data: partial")
        parser.reset()

        assertThat(parser.append("")).isNull()
    }

    @Test
    fun aMultiLineDataFieldWithACrlfStreamIsJoined() {
        val parser = SseByteStreamParser()
        val stream = "data: line one\r\ndata: line two\r\n\r\n"

        val events = stream.encodeToByteArray().toList().mapNotNull { parser.append(it) }

        assertThat(events).containsExactly(SseEvent(name = null, data = "line one\nline two"))
    }
}
