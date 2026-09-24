// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

/** Ported from `ios/OpenFlight/SSEEventParser.swift`'s `SSEEvent`. */
data class SseEvent(
    val name: String?,
    val data: String,
)

/**
 * Incremental parser for the subset of Server-Sent Events OpenFlight uses: `event:` and `data:`
 * fields, comment lines used as heartbeats, and a blank line to dispatch. Fed one complete line
 * at a time by [SseByteStreamParser] and by literals in tests. Ported from
 * `ios/OpenFlight/SSEEventParser.swift`'s `SSEEventParser`.
 */
class SseEventParser {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    /** Feeds one line, returning an event when that line completes one. */
    fun append(line: String): SseEvent? {
        // The byte adapter strips LF, but a CRLF stream leaves the carriage return behind.
        val trimmed = if (line.endsWith("\r")) line.dropLast(1) else line

        return when {
            trimmed.isEmpty() -> {
                dispatch()
            }

            // A line beginning with a colon is a comment. The server sends these as heartbeats
            // so idle connections stay open and dead ones get noticed.
            trimmed.startsWith(":") -> {
                null
            }

            else -> {
                applyField(trimmed)
                null
            }
        }
    }

    private fun applyField(line: String) {
        val (field, value) = splitSseField(line)
        when (field) {
            "event" -> eventName = value

            "data" -> dataLines.add(value)

            // `id` and `retry` are unused, and unknown fields must be ignored.
            else -> Unit
        }
    }

    fun reset() {
        eventName = null
        dataLines.clear()
    }

    private fun dispatch(): SseEvent? {
        val event = if (dataLines.isEmpty()) null else SseEvent(eventName, dataLines.joinToString("\n"))
        reset()
        return event
    }
}

/**
 * Splits `field: value`, dropping one optional space after the colon. A line with no colon is a
 * field name with an empty value.
 */
private fun splitSseField(line: String): Pair<String, String> {
    val colon = line.indexOf(':')
    if (colon < 0) return line to ""
    val field = line.substring(0, colon)
    val rawValue = line.substring(colon + 1)
    val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
    return field to value
}

/**
 * Converts raw response bytes into SSE lines without losing empty lines. Ported from
 * `ios/OpenFlight/SSEEventParser.swift`'s `SSEByteStreamParser`.
 *
 * A line-oriented byte sequence often drops the empty line separating SSE events, which would
 * leave every event buffered indefinitely. This adapter retains that protocol-significant line.
 */
class SseByteStreamParser {
    private val lineBytes = mutableListOf<Byte>()
    private val eventParser = SseEventParser()

    fun append(byte: Byte): SseEvent? {
        if (byte != LINE_FEED) {
            lineBytes.add(byte)
            return null
        }
        val line = lineBytes.toByteArray().decodeToString()
        lineBytes.clear()
        return eventParser.append(line)
    }

    fun reset() {
        lineBytes.clear()
        eventParser.reset()
    }

    private companion object {
        const val LINE_FEED: Byte = 0x0A
    }
}
