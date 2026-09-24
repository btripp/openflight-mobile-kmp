// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * In-memory shot history, ported from `ios/OpenFlight/ShotHistory.swift`.
 *
 * Shots are newest-first, capped at [maximumCount], and deduplicated by [ShotEvent.eventId].
 * Unlike the Swift `struct` (which mutates in place), this is an immutable value: [record]
 * returns the updated history so it composes naturally with `StateFlow`.
 */
data class ShotHistory(
    val maximumCount: Int = DEFAULT_MAXIMUM_COUNT,
    val shots: List<ShotEvent> = emptyList(),
) {
    init {
        require(maximumCount > 0) { "Shot history must retain at least one shot" }
    }

    val latestShot: ShotEvent? get() = shots.firstOrNull()

    /** Returns this history with [shot] inserted newest-first, or unchanged if already present. */
    fun record(shot: ShotEvent): ShotHistory {
        if (shots.any { it.eventId == shot.eventId }) return this
        val updated = listOf(shot) + shots
        return copy(shots = updated.take(maximumCount))
    }

    companion object {
        const val DEFAULT_MAXIMUM_COUNT = 100
    }
}
