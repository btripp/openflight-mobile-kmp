// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import kotlin.math.ceil

/** Ported from `ios/OpenFlight/BLEFrameReassembler.swift`'s `BLEFrameError`. */
sealed class BleFrameError(
    message: String,
) : Exception(message) {
    object EmptyPayload : BleFrameError("A BLE message cannot be empty.")

    object PayloadTooLarge : BleFrameError("The BLE message is too large.")

    object InvalidSize : BleFrameError("Received a BLE frame with an invalid size.")

    data class UnsupportedVersion(
        val version: Int,
    ) : BleFrameError("BLE frame version $version is not supported.")

    object InvalidMetadata : BleFrameError("Received invalid BLE fragment metadata.")

    object InconsistentFragmentCount :
        BleFrameError("Fragments for one BLE message disagree about its size.")
}

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

// Byte offsets within the 5-byte `>BHBB` header: version, sequence-high, sequence-low, index,
// fragment count.
private const val VERSION_OFFSET = 0
private const val SEQUENCE_HIGH_OFFSET = 1
private const val SEQUENCE_LOW_OFFSET = 2
private const val INDEX_OFFSET = 3
private const val COUNT_OFFSET = 4

/**
 * Splits a payload into ≤20-byte BLE notification frames, ported from
 * `ios/OpenFlight/BLEFrameReassembler.swift`'s `BLEFrameEncoder`. Byte layout matches the
 * authoritative Python encoder in `src/openflight/ble/protocol.py`: a 5-byte big-endian header
 * `>BHBB` = version(1), sequence(u16), index(u8), count(u8), followed by up to 15 payload bytes.
 */
object BleFrameEncoder {
    const val MAXIMUM_FRAGMENT_COUNT = 255
    const val FRAGMENT_PAYLOAD_SIZE =
        BleFrameReassembler.MAXIMUM_FRAME_SIZE - BleFrameReassembler.HEADER_SIZE

    /** [sequence] is a 16-bit message sequence in `0..65535`; callers wrap it themselves. */
    fun frames(
        payload: ByteArray,
        sequence: Int,
    ): List<ByteArray> {
        if (payload.isEmpty()) throw BleFrameError.EmptyPayload
        val fragmentCount = ceil(payload.size.toDouble() / FRAGMENT_PAYLOAD_SIZE).toInt()
        if (fragmentCount > MAXIMUM_FRAGMENT_COUNT) throw BleFrameError.PayloadTooLarge

        return (0 until fragmentCount).map { index -> frameAt(payload, sequence, fragmentCount, index) }
    }

    private fun frameAt(
        payload: ByteArray,
        sequence: Int,
        fragmentCount: Int,
        index: Int,
    ): ByteArray {
        val start = index * FRAGMENT_PAYLOAD_SIZE
        val end = minOf(start + FRAGMENT_PAYLOAD_SIZE, payload.size)
        val frame = ByteArray(BleFrameReassembler.HEADER_SIZE + (end - start))
        frame[VERSION_OFFSET] = BleFrameReassembler.FRAME_VERSION.toByte()
        frame[SEQUENCE_HIGH_OFFSET] = ((sequence shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        frame[SEQUENCE_LOW_OFFSET] = (sequence and BYTE_MASK).toByte()
        frame[INDEX_OFFSET] = index.toByte()
        frame[COUNT_OFFSET] = fragmentCount.toByte()
        payload.copyInto(frame, BleFrameReassembler.HEADER_SIZE, start, end)
        return frame
    }
}

/** One parsed BLE frame header: `>BHBB` = version, sequence, index, count. */
private data class BleFrameHeader(
    val sequence: Int,
    val index: Int,
    val count: Int,
)

/**
 * Reassembles BLE notification frames into one message, ported from
 * `ios/OpenFlight/BLEFrameReassembler.swift`.
 *
 * A new sequence discards any in-flight partial message and starts over. A frame that repeats
 * the current sequence but disagrees about the fragment count resets and throws. Duplicate
 * fragment indexes overwrite harmlessly. The message is emitted once all `count` fragments for
 * the current sequence have arrived, in any order.
 */
class BleFrameReassembler {
    private var sequence: Int? = null
    private var fragmentCount: Int? = null
    private val fragments = mutableMapOf<Int, ByteArray>()

    fun append(frame: ByteArray): ByteArray? {
        val header = parseHeader(frame)
        applySequence(header.sequence, header.count)
        fragments[header.index] = frame.copyOfRange(HEADER_SIZE, frame.size)
        return assembleIfComplete(header.count)
    }

    fun reset() {
        sequence = null
        fragmentCount = null
        fragments.clear()
    }

    private fun parseHeader(frame: ByteArray): BleFrameHeader {
        validateSize(frame)
        validateVersion(frame[VERSION_OFFSET].toInt() and BYTE_MASK)

        val highByte = frame[SEQUENCE_HIGH_OFFSET].toInt() and BYTE_MASK
        val lowByte = frame[SEQUENCE_LOW_OFFSET].toInt() and BYTE_MASK
        val incomingSequence = (highByte shl BITS_PER_BYTE) or lowByte
        val index = frame[INDEX_OFFSET].toInt() and BYTE_MASK
        val count = frame[COUNT_OFFSET].toInt() and BYTE_MASK
        validateMetadata(index, count)

        return BleFrameHeader(incomingSequence, index, count)
    }

    private fun validateSize(frame: ByteArray) {
        if (frame.size < HEADER_SIZE || frame.size > MAXIMUM_FRAME_SIZE) {
            throw BleFrameError.InvalidSize
        }
    }

    private fun validateVersion(version: Int) {
        if (version != FRAME_VERSION) throw BleFrameError.UnsupportedVersion(version)
    }

    private fun validateMetadata(
        index: Int,
        count: Int,
    ) {
        if (count == 0 || index >= count) throw BleFrameError.InvalidMetadata
    }

    private fun applySequence(
        incomingSequence: Int,
        incomingCount: Int,
    ) {
        if (sequence != incomingSequence) {
            reset()
            sequence = incomingSequence
            fragmentCount = incomingCount
        } else if (fragmentCount != incomingCount) {
            reset()
            throw BleFrameError.InconsistentFragmentCount
        }
    }

    private fun assembleIfComplete(count: Int): ByteArray? {
        if (fragments.size != count) return null
        val message = concatenateFragments(count)
        reset()
        return message
    }

    /**
     * [fragments] only ever holds indexes in `0 until count` for the current sequence (see
     * [validateMetadata] and the reset-on-new-sequence rule in [applySequence]), so once its size
     * equals `count`, every index in that range is present by construction.
     */
    private fun concatenateFragments(count: Int): ByteArray {
        val ordered = (0 until count).map { fragments.getValue(it) }
        val message = ByteArray(ordered.sumOf { it.size })
        var offset = 0
        for (fragment in ordered) {
            fragment.copyInto(message, offset)
            offset += fragment.size
        }
        return message
    }

    companion object {
        const val FRAME_VERSION = 1
        const val HEADER_SIZE = 5
        const val MAXIMUM_FRAME_SIZE = 20
    }
}
