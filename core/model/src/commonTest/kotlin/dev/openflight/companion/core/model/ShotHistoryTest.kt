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

    // Plan R8e: a v2 final shot replaces its provisional version (same event_id) in place.

    @Test
    fun aV2FinalShotReplacesItsProvisionalInPlace() {
        val older = makeShotEvent(eventId = "22222222-2222-2222-2222-222222222222")
        val provisional = v2Shot(final = false, ballSpeedMph = 100.0)
        val final = v2Shot(final = true, ballSpeedMph = 106.1)

        val history =
            ShotHistory()
                .record(older)
                .record(provisional)
                .record(older)
                .record(final)

        assertThat(history.shots).containsExactly(final, older)
    }

    @Test
    fun aProvisionalShotNeverReplacesItsFinalVersion() {
        val final = v2Shot(final = true, ballSpeedMph = 106.1)

        val history = ShotHistory().record(final).record(v2Shot(final = false, ballSpeedMph = 100.0))

        assertThat(history.shots).containsExactly(final)
    }

    @Test
    fun aV1ShotWithAKnownEventIdIsStillIgnored() {
        val first = makeShotEvent(ballSpeedMph = 140.0)

        val history = ShotHistory().record(first).record(first.copy(ballSpeedMph = 150.0))

        assertThat(history.shots).containsExactly(first)
    }

    @Test
    fun removeTimestampDropsTheDeletedShot() {
        val kept =
            makeShotEvent(
                eventId = "22222222-2222-2222-2222-222222222222",
            ).copy(timestamp = "2026-09-25T10:00:00")
        val deleted = v2Shot(final = true, ballSpeedMph = 106.1)
        val history = ShotHistory().record(kept).record(deleted)

        assertThat(history.removeTimestamp(deleted.timestamp).shots).containsExactly(kept)
        assertThat(history.removeTimestamp("2020-01-01T00:00:00")).isEqualTo(history)
    }

    private fun v2Shot(
        final: Boolean,
        ballSpeedMph: Double,
    ): ShotEvent =
        makeShotEvent(eventId = "05dd37ec-49ed-596b-b1a4-953d54e4f239", ballSpeedMph = ballSpeedMph).copy(
            schemaVersion = 2,
            timestamp = "2026-09-25T14:03:07.412345",
            type = "shot",
            final = final,
        )
}
