// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/** assertk 0.28 has no dedicated `ByteArray` content assertion, so compare as lists instead. */
private fun assertBytesEqual(
    actual: ByteArray,
    expected: ByteArray,
) {
    assertThat(actual.toList()).isEqualTo(expected.toList())
}

class BleFrameReassemblerTest {
    @Test
    fun reassemblesOrderedAndOutOfOrderFrames() {
        val payload = "a payload that requires several BLE fragments".encodeToByteArray()
        val frames = makeBleFrames(payload, sequence = 42)

        val ordered = BleFrameReassembler()
        var orderedResult: ByteArray? = null
        for (frame in frames) orderedResult = ordered.append(frame) ?: orderedResult

        val reversed = BleFrameReassembler()
        var reversedResult: ByteArray? = null
        for (frame in frames.reversed()) reversedResult = reversed.append(frame) ?: reversedResult

        assertBytesEqual(orderedResult!!, payload)
        assertBytesEqual(reversedResult!!, payload)
    }

    @Test
    fun duplicateFragmentIsIgnored() {
        val payload = "this message needs more than one frame".encodeToByteArray()
        val frames = makeBleFrames(payload, sequence = 7)
        val reassembler = BleFrameReassembler()

        assertThat(reassembler.append(frames[0])).isNull()
        assertThat(reassembler.append(frames[0])).isNull()

        var result: ByteArray? = null
        for (frame in frames.drop(1)) result = reassembler.append(frame) ?: result
        assertBytesEqual(result!!, payload)
    }

    @Test
    fun newSequenceReplacesIncompleteMessage() {
        val oldFrames = makeBleFrames("old incomplete message".encodeToByteArray(), sequence = 1)
        val newPayload = "new complete message".encodeToByteArray()
        val newFrames = makeBleFrames(newPayload, sequence = 2)
        val reassembler = BleFrameReassembler()

        assertThat(reassembler.append(oldFrames[0])).isNull()
        var result: ByteArray? = null
        for (frame in newFrames) result = reassembler.append(frame) ?: result

        assertBytesEqual(result!!, newPayload)
    }

    @Test
    fun rejectsAFourByteFrame() {
        val reassembler = BleFrameReassembler()
        assertFailure { reassembler.append(byteArrayOf(1, 0, 1, 0)) }
            .isInstanceOf<BleFrameError.InvalidSize>()
    }

    @Test
    fun rejectsATwentyOneByteFrame() {
        val reassembler = BleFrameReassembler()
        val oversized = ByteArray(21) { 1 }
        assertFailure { reassembler.append(oversized) }.isInstanceOf<BleFrameError.InvalidSize>()
    }

    @Test
    fun rejectsFrameVersionTwo() {
        val reassembler = BleFrameReassembler()
        assertFailure { reassembler.append(byteArrayOf(2, 0, 1, 0, 1, 65)) }
            .isInstanceOf<BleFrameError.UnsupportedVersion>()
    }

    @Test
    fun rejectsZeroFragmentCount() {
        val reassembler = BleFrameReassembler()
        assertFailure { reassembler.append(byteArrayOf(1, 0, 1, 0, 0, 65)) }
            .isInstanceOf<BleFrameError.InvalidMetadata>()
    }

    @Test
    fun rejectsIndexGreaterThanOrEqualToCount() {
        val reassembler = BleFrameReassembler()
        assertFailure { reassembler.append(byteArrayOf(1, 0, 1, 2, 1, 65)) }
            .isInstanceOf<BleFrameError.InvalidMetadata>()
    }

    @Test
    fun sameSequenceWithADifferentCountResetsAndThrows() {
        val reassembler = BleFrameReassembler()
        // sequence 1, index 0, count 2
        assertThat(reassembler.append(byteArrayOf(1, 0, 1, 0, 2, 65))).isNull()
        // same sequence 1, but now claims count 3 -> inconsistent
        assertFailure { reassembler.append(byteArrayOf(1, 0, 1, 1, 3, 66)) }
            .isInstanceOf<BleFrameError.InconsistentFragmentCount>()

        // The reassembler reset, so a fresh sequence starts cleanly.
        assertBytesEqual(reassembler.append(byteArrayOf(1, 0, 9, 0, 1, 67))!!, byteArrayOf(67))
    }

    @Test
    fun theU16SequenceWraps() {
        val payload = byteArrayOf(9)
        val reassembler = BleFrameReassembler()

        // Complete a message at the maximum sequence value.
        assertBytesEqual(
            reassembler.append(byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0, 1, 9))!!,
            payload,
        )

        // The next message wraps back to sequence 0 and must still complete cleanly.
        assertBytesEqual(reassembler.append(byteArrayOf(1, 0, 0, 0, 1, 9))!!, payload)
    }

    @Test
    fun productionEncoderRoundTripsLargerControlPayload() {
        val payload = ByteArray(380) { 0x41 }
        val frames = BleFrameEncoder.frames(payload, sequence = 91)
        val reassembler = BleFrameReassembler()
        var result: ByteArray? = null

        for (frame in frames) result = reassembler.append(frame) ?: result

        assertBytesEqual(result!!, payload)
        assertThat(frames.all { it.size <= BleFrameReassembler.MAXIMUM_FRAME_SIZE }).isTrue()
    }
}
