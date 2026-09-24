// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import kotlin.test.Test

class BleFrameEncoderTest {
    @Test
    fun rejectsAnEmptyPayload() {
        assertFailure { BleFrameEncoder.frames(ByteArray(0), sequence = 1) }
            .isInstanceOf<BleFrameError.EmptyPayload>()
    }

    @Test
    fun rejectsAPayloadThatNeedsMoreThanTheMaximumFragmentCount() {
        val tooLarge = ByteArray(BleFrameEncoder.FRAGMENT_PAYLOAD_SIZE * BleFrameEncoder.MAXIMUM_FRAGMENT_COUNT + 1)
        assertFailure { BleFrameEncoder.frames(tooLarge, sequence = 1) }
            .isInstanceOf<BleFrameError.PayloadTooLarge>()
    }

    @Test
    fun everyFrameIsAtMostTwentyBytes() {
        val payload = ByteArray(100) { it.toByte() }
        val frames = BleFrameEncoder.frames(payload, sequence = 5)
        assertThat(frames.all { it.size <= BleFrameReassembler.MAXIMUM_FRAME_SIZE }).isTrue()
    }
}
