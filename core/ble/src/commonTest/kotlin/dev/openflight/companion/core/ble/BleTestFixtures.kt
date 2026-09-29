// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.protocol.BleFrameReassembler
import dev.openflight.companion.core.protocol.OpenFlightJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Ported from `ios/OpenFlightTests/BLETestFixtures.swift`'s `makeBLEFrames`: an encoder written
 * independently of `BleFrameEncoder`, so transport tests don't share a bug with production code.
 */
internal fun makeBleFrames(
    payload: ByteArray,
    sequence: Int,
): List<ByteArray> {
    val chunkSize = 15
    val count = (payload.size + chunkSize - 1) / chunkSize
    return (0 until count).map { index ->
        val start = index * chunkSize
        val end = minOf(start + chunkSize, payload.size)
        byteArrayOf(1, (sequence shr 8).toByte(), (sequence and 0xFF).toByte(), index.toByte(), count.toByte()) +
            payload.copyOfRange(start, end)
    }
}

internal fun makeBleFrames(
    json: String,
    sequence: Int,
): List<ByteArray> = makeBleFrames(json.encodeToByteArray(), sequence)

/** The backend's `tests/fixtures/shot_v2.json`, the shared schema 2 shot contract fixture. */
internal val SHOT_V2_FIXTURE_JSON: String get() = BleContractFixtures.files.getValue("shot_v2")

/** A minimal schema 2 final shot, as the Pi notifies it on the shot characteristic. */
internal fun shotJson(
    eventId: String,
    ballSpeedMph: Double = 140.0,
): String =
    """{"ball_speed_mph":$ballSpeedMph,"club":"driver","estimated_carry_yards":250.0,"event_id":"$eventId",""" +
        """"final":true,"schema_version":2,"timestamp":"2026-08-05T23:54:00","type":"shot"}"""

internal fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) or this[i * 2 + 1].digitToInt(16)).toByte() }

internal fun ByteArray.frameSequence(): Int = ((this[1].toInt() and 0xFF) shl 8) or (this[2].toInt() and 0xFF)

/** Reassembles what the transport wrote to the control characteristic back into JSON objects. */
internal fun decodeWrittenCommands(frames: List<ByteArray>): List<JsonObject> {
    val reassembler = BleFrameReassembler()
    return frames.mapNotNull { frame ->
        reassembler.append(frame)?.let { OpenFlightJson.parseToJsonElement(it.decodeToString()).jsonObject }
    }
}

internal val JsonObject.requestId: String get() = (getValue("request_id") as JsonPrimitive).content

internal fun clubResponse(
    requestId: String,
    club: String,
    status: String = "ok",
): String = """{"ok":true,"request_id":"$requestId","result":{"club":"$club","status":"$status"},"schema_version":2}"""
