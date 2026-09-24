// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.Json
import kotlin.test.Test

class GolfClubTest {
    private val json = Json

    @Test
    fun everyRawWireValueRoundTripsAndHasTheExpectedDisplayName() {
        val expected =
            listOf(
                "driver" to "Driver",
                "3-wood" to "3-Wood",
                "5-wood" to "5-Wood",
                "7-wood" to "7-Wood",
                "3-hybrid" to "3-Hybrid",
                "5-hybrid" to "5-Hybrid",
                "7-hybrid" to "7-Hybrid",
                "9-hybrid" to "9-Hybrid",
                "2-iron" to "2-Iron",
                "3-iron" to "3-Iron",
                "4-iron" to "4-Iron",
                "5-iron" to "5-Iron",
                "6-iron" to "6-Iron",
                "7-iron" to "7-Iron",
                "8-iron" to "8-Iron",
                "9-iron" to "9-Iron",
                "pw" to "Pitching Wedge",
                "gw" to "Gap Wedge",
                "sw" to "Sand Wedge",
                "lw" to "Lob Wedge",
            )

        assertThat(expected.size).isEqualTo(20)
        for ((wireValue, displayName) in expected) {
            val club = json.decodeFromString(GolfClub.serializer(), "\"$wireValue\"")
            assertThat(club.wireValue).isEqualTo(wireValue)
            assertThat(club.displayName).isEqualTo(displayName)
            assertThat(json.encodeToString(GolfClub.serializer(), club)).isEqualTo("\"$wireValue\"")
        }
    }
}
