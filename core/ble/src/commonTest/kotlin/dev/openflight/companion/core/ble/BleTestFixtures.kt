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

/** `ios/OpenFlightTests/Fixtures/shot_v1.json`, the shared contract fixture (copied: test sources aren't shared). */
internal const val SHOT_V1_FIXTURE_JSON = """
{
  "ball_speed_mph": 151.4,
  "club": "driver",
  "club_path_deg": 2.1,
  "club_speed_mph": 103.2,
  "estimated_carry_yards": 264,
  "event_id": "B0D91F0A-7950-4D7E-9DD5-AF9777C190E1",
  "launch_angle_horizontal": -1.3,
  "launch_angle_vertical": 12.6,
  "schema_version": 1,
  "smash_factor": 1.47,
  "spin_axis_deg": -3.4,
  "spin_rpm": 2380,
  "timestamp": "2026-07-29T19:42:10.123456"
}
"""

internal fun shotJson(
    eventId: String,
    ballSpeedMph: Double = 140.0,
): String =
    """{"schema_version":1,"event_id":"$eventId","timestamp":"2026-08-05T23:54:00","club":"driver",""" +
        """"ball_speed_mph":$ballSpeedMph,"estimated_carry_yards":250.0}"""

/**
 * The S2 golden frames (core:protocol `GoldenFrameTest`): the Python encoder's 23 frames for the
 * shared fixture at sequence 0x0102.
 */
internal val GOLDEN_FRAME_HEX =
    listOf(
        "01010200177b2262616c6c5f73706565645f6d70",
        "010102011768223a3135312e342c22636c756222",
        "01010202173a22647269766572222c22636c7562",
        "01010203175f706174685f646567223a322e312c",
        "010102041722636c75625f73706565645f6d7068",
        "0101020517223a3130332e322c22657374696d61",
        "01010206177465645f63617272795f7961726473",
        "0101020717223a3236342c226576656e745f6964",
        "0101020817223a2242304439314630412d373935",
        "0101020917302d344437452d394444352d414639",
        "0101020a17373737433139304531222c226c6175",
        "0101020b176e63685f616e676c655f686f72697a",
        "0101020c176f6e74616c223a2d312e332c226c61",
        "0101020d17756e63685f616e676c655f76657274",
        "0101020e176963616c223a31322e362c22736368",
        "0101020f17656d615f76657273696f6e223a312c",
        "010102101722736d6173685f666163746f72223a",
        "0101021117312e34372c227370696e5f61786973",
        "01010212175f646567223a2d332e342c22737069",
        "01010213176e5f72706d223a323338302c227469",
        "01010214176d657374616d70223a22323032362d",
        "010102151730372d32395431393a34323a31302e",
        "0101021617313233343536227d",
    )

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
): String = """{"ok":true,"request_id":"$requestId","result":{"club":"$club","status":"$status"},"schema_version":1}"""
