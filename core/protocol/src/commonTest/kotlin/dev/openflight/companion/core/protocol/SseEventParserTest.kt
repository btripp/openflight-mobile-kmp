// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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

    @Test
    fun aLineWithNoNewlinePastTheCapThrows() {
        val parser = SseByteStreamParser()
        val maxLine =
            "data: ".encodeToByteArray() + ByteArray(SseByteStreamParser.MAX_LINE_BYTES - 6) { 'x'.code.toByte() }
        maxLine.forEach { assertThat(parser.append(it)).isNull() }

        assertFailure { parser.append('x'.code.toByte()) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun aLineAtTheCapStillParses() {
        val parser = SseByteStreamParser()
        val payload = "x".repeat(SseByteStreamParser.MAX_LINE_BYTES - 6)
        val events = "data: $payload\n\n".encodeToByteArray().toList().mapNotNull { parser.append(it) }

        assertThat(events).containsExactly(SseEvent(name = null, data = payload))
    }

    @Test
    fun endlessDataLinesPastTheEventCapThrow() {
        val parser = SseEventParser()
        val line = "data: " + "x".repeat(1023)
        repeat(SseEventParser.MAX_EVENT_CHARS / 1024) { assertThat(parser.append(line)).isNull() }

        assertFailure { parser.append(line) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun resetAfterAnOversizedEventAcceptsTheNextOne() {
        val parser = SseEventParser()
        val line = "data: " + "x".repeat(1023)
        assertFailure { repeat(SseEventParser.MAX_EVENT_CHARS / 1024 + 1) { parser.append(line) } }

        parser.reset()
        parser.append("data: next")

        assertThat(parser.append("")).isEqualTo(SseEvent(name = null, data = "next"))
    }
}
