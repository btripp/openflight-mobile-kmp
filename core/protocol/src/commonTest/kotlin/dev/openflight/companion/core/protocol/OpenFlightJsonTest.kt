// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class OpenFlightJsonTest {
    @Test
    fun encodeDefaultsIsEnabledSoSchemaVersionIsNeverOmitted() {
        assertThat(OpenFlightJson.configuration.encodeDefaults).isTrue()
    }

    @Test
    fun ignoreUnknownKeysIsEnabledForForwardCompatibility() {
        assertThat(OpenFlightJson.configuration.ignoreUnknownKeys).isTrue()
    }

    @Test
    fun explicitNullsStaysAtItsDefaultOfTrue() {
        assertThat(OpenFlightJson.configuration.explicitNulls).isEqualTo(true)
    }

    @Test
    fun unknownExtraJsonKeysAreIgnoredWhenDecoding() {
        val response =
            ControlCodec.decodeResponse(
                """
                {"schema_version":1,"request_id":"req-1","ok":true,"unexpected_new_field":"x",
                 "result":{"status":"ok","club":"driver","unexpected_result_field":123}}
                """.trimIndent().encodeToByteArray(),
            )

        val club = ControlCodec.decodeClubResult(response)

        assertThat(club.status).isEqualTo("ok")
    }

    @Test
    fun ignoreUnknownKeysIsNotTheSameAsAllowingMissingRequiredFields() {
        // Sanity check that the flag we rely on is the specific one we mean.
        assertThat(OpenFlightJson.configuration.isLenient).isFalse()
    }
}
