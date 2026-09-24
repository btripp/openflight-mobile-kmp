// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import kotlin.test.Test

class ShotEventDecoderTest {
    @Test
    fun decodesTheSharedV1Fixture() {
        val decoder = ShotEventDecoder()

        val shot = decoder.decode(SHOT_V1_FIXTURE_JSON)

        assertThat(shot?.eventId).isEqualTo("B0D91F0A-7950-4D7E-9DD5-AF9777C190E1")
    }

    @Test
    fun rejectsSchemaVersionTwo() {
        val decoder = ShotEventDecoder()

        assertFailure {
            decoder.decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111", schemaVersion = 2))
        }.isInstanceOf<ShotDecodeError.UnsupportedSchema>()
    }

    @Test
    fun suppressesAReplayedEventId() {
        val decoder = ShotEventDecoder()
        val payload = shotJson(eventId = "11111111-1111-1111-1111-111111111111")

        val first = decoder.decode(payload)
        val replay = decoder.decode(payload)

        assertThat(first).isEqualTo(first)
        assertThat(replay).isNull()
    }

    @Test
    fun resetAllowsTheLastEventToBeSeenAgain() {
        val decoder = ShotEventDecoder()
        val payload = shotJson(eventId = "11111111-1111-1111-1111-111111111111")

        decoder.decode(payload)
        decoder.reset()
        val afterReset = decoder.decode(payload)

        assertThat(afterReset?.eventId).isEqualTo("11111111-1111-1111-1111-111111111111")
    }

    @Test
    fun aDifferentEventIdIsNotSuppressed() {
        val decoder = ShotEventDecoder()
        decoder.decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111"))

        val second = decoder.decode(shotJson(eventId = "22222222-2222-2222-2222-222222222222"))

        assertThat(second?.eventId).isEqualTo("22222222-2222-2222-2222-222222222222")
    }
}
