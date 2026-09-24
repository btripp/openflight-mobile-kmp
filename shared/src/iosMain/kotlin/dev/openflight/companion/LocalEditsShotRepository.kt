// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The `--ui-testing`/`--preview-shot` repository ([PreviewShotRepository]) with the phone-local
 * history edits of the real repository: [deleteShot], [deleteShotByTimestamp] and [clearHistory]
 * remove shots from [history] and [latestShot], so the iOS session screen's swipe-to-delete and
 * Clear can be exercised by XCUITests without a Pi. Debug launch hooks only; iOS-only because the
 * Android device UI tests drive the session screen with their own fakes.
 */
internal class LocalEditsShotRepository(
    private val delegate: ShotRepository,
) : ShotRepository by delegate {
    private val shots = MutableStateFlow(delegate.history.value)
    private val latest = MutableStateFlow(delegate.latestShot.value)

    override val history: StateFlow<List<ShotEvent>> = shots
    override val latestShot: StateFlow<ShotEvent?> = latest

    override fun deleteShot(eventId: String) = update { it.eventId != eventId }

    override fun deleteShotByTimestamp(timestamp: String) = update { it.timestamp != timestamp }

    override fun clearHistory() = update { false }

    private fun update(keep: (ShotEvent) -> Boolean) {
        shots.value = shots.value.filter(keep)
        latest.value = shots.value.firstOrNull()
    }
}
