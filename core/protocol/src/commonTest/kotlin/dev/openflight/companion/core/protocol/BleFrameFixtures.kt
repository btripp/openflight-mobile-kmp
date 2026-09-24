// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

/** Mirrors `ios/OpenFlightTests/BLETestFixtures.swift`'s `makeBLEFrames`, a reference encoder
 * independent of [BleFrameEncoder] used to build test fixtures. */
internal fun makeBleFrames(
    payload: ByteArray,
    sequence: Int,
): List<ByteArray> {
    val chunkSize = 15
    val count = (payload.size + chunkSize - 1) / chunkSize
    var start = 0
    var index = 0
    val frames = mutableListOf<ByteArray>()
    while (start < payload.size) {
        val end = minOf(start + chunkSize, payload.size)
        val frame = ByteArray(5 + (end - start))
        frame[0] = 1
        frame[1] = ((sequence shr 8) and 0xFF).toByte()
        frame[2] = (sequence and 0xFF).toByte()
        frame[3] = index.toByte()
        frame[4] = count.toByte()
        payload.copyInto(frame, 5, start, end)
        frames.add(frame)
        start = end
        index++
    }
    return frames
}
