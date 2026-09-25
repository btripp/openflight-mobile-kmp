// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.model

/**
 * In-memory shot history, ported from `ios/OpenFlight/ShotHistory.swift`.
 *
 * Shots are newest-first, capped at [maximumCount], and deduplicated by [ShotEvent.eventId]. A
 * schema v2 shot upserts instead: its final version replaces the provisional one in place.
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

    /**
     * Returns this history with [shot] inserted newest-first. A shot whose [ShotEvent.eventId] is
     * already present leaves it unchanged (v1), unless [shot] is a v2 update of it: then it replaces
     * the old version in place, keeping its position. A provisional shot never replaces a final one.
     */
    fun record(shot: ShotEvent): ShotHistory {
        val index = shots.indexOfFirst { it.eventId == shot.eventId }
        if (index < 0) return copy(shots = (listOf(shot) + shots).take(maximumCount))
        val existing = shots[index]
        val replaces = shot.schemaVersion >= 2 && existing != shot && !(existing.final == true && shot.isProvisional)
        return if (replaces) copy(shots = shots.toMutableList().also { it[index] = shot }) else this
    }

    /** Returns this history without the shots whose [ShotEvent.timestamp] is [timestamp] (a v2 `shot_deleted`). */
    fun removeTimestamp(timestamp: String): ShotHistory =
        if (shots.none { it.timestamp == timestamp }) {
            this
        } else {
            copy(
                shots = shots.filterNot { it.timestamp == timestamp },
            )
        }

    companion object {
        const val DEFAULT_MAXIMUM_COUNT = 100
    }
}
