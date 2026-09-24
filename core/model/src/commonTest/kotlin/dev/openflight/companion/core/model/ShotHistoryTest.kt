// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test

class ShotHistoryTest {
    @Test
    fun recordKeepsNewestShotFirstAndIgnoresDuplicates() {
        val first = makeShotEvent(eventId = "11111111-1111-1111-1111-111111111111", ballSpeedMph = 140.0)
        val second = makeShotEvent(eventId = "22222222-2222-2222-2222-222222222222", ballSpeedMph = 151.0)
        var history = ShotHistory(maximumCount = 10)

        history = history.record(first)
        history = history.record(second)
        history = history.record(first)

        assertThat(history.shots.map { it.eventId }).containsExactly(second.eventId, first.eventId)
        assertThat(history.latestShot).isEqualTo(second)
    }

    @Test
    fun recordDropsOldestShotsAtCapacity() {
        val first = makeShotEvent(eventId = "11111111-1111-1111-1111-111111111111", ballSpeedMph = 140.0)
        val second = makeShotEvent(eventId = "22222222-2222-2222-2222-222222222222", ballSpeedMph = 145.0)
        val third = makeShotEvent(eventId = "33333333-3333-3333-3333-333333333333", ballSpeedMph = 150.0)
        var history = ShotHistory(maximumCount = 2)

        history = history.record(first)
        history = history.record(second)
        history = history.record(third)

        assertThat(history.shots.map { it.eventId }).containsExactly(third.eventId, second.eventId)
    }
}
