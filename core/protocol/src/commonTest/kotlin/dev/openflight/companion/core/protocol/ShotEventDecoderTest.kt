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

    // Plan R8e: schema 2 is accepted now; anything past it is still rejected.
    @Test
    fun rejectsSchemaVersionThree() {
        val decoder = ShotEventDecoder()

        assertFailure {
            decoder.decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111", schemaVersion = 3))
        }.isInstanceOf<ShotDecodeError.UnsupportedSchema>()
    }

    @Test
    fun rejectsSchemaVersionZero() {
        assertFailure {
            ShotEventDecoder().decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111", schemaVersion = 0))
        }.isInstanceOf<ShotDecodeError.UnsupportedSchema>()
    }

    @Test
    fun acceptsSchemaVersionTwo() {
        val shot =
            ShotEventDecoder().decode(
                shotJson(eventId = "11111111-1111-1111-1111-111111111111", schemaVersion = 2),
            )

        assertThat(shot?.schemaVersion).isEqualTo(2)
    }

    @Test
    fun aV2PayloadThatIsNotAShotIsRejected() {
        assertFailure {
            ShotEventDecoder().decode(v2Shot(final = true, type = "profiles"))
        }.isInstanceOf<ShotDecodeError.NotAShot>()
    }

    @Test
    fun aV2FinalShotPassesAfterItsProvisionalButEachReplayIsSuppressed() {
        val decoder = ShotEventDecoder()

        val provisional = decoder.decode(v2Shot(final = false))
        val provisionalReplay = decoder.decode(v2Shot(final = false))
        val final = decoder.decode(v2Shot(final = true))
        val finalReplay = decoder.decode(v2Shot(final = true))

        assertThat(provisional?.isProvisional).isEqualTo(true)
        assertThat(provisionalReplay).isNull()
        assertThat(final?.final).isEqualTo(true)
        assertThat(finalReplay).isNull()
    }

    @Test
    fun aV1ShotKeepsTheEventIdAloneAsItsReplayKey() {
        val decoder = ShotEventDecoder()
        decoder.decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111"))

        // A v1 payload never carries `final`; the same id is a replay whatever else it says.
        assertThat(decoder.decode(shotJson(eventId = "11111111-1111-1111-1111-111111111111"))).isNull()
    }

    private fun v2Shot(
        final: Boolean,
        type: String = "shot",
    ): String =
        """{"schema_version":2,"type":"$type","final":$final,"event_id":"05dd37ec-49ed-596b-b1a4-953d54e4f239",""" +
            """"timestamp":"2026-09-25T14:03:07.412345","club":"7-iron","ball_speed_mph":106.1,""" +
            """"estimated_carry_yards":152,"enrichment":{"status":"${if (final) "complete" else "pending"}"}}"""

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
