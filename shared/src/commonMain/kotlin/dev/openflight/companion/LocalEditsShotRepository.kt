// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.PiLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The `--ui-testing`/`--preview-shot` repository ([PreviewShotRepository]) with the phone-local
 * history edits of the real repository: [deleteShot], [deleteShotByTimestamp] and [clearHistory]
 * remove shots from [history] and [latestShot], so the session screen's swipe-to-delete, card
 * Delete and Clear work without a Pi (XCUITests on iOS, manual checks on both platforms). Debug
 * launch hooks only.
 *
 * With a connected [pi] (only `--preview-pi-session`'s [PreviewPiSessionRepository] ever is), a
 * delete by timestamp and a clear go to it instead, like the real repository routes them to the Pi.
 */
internal class LocalEditsShotRepository(
    private val delegate: ShotRepository,
    private val pi: PiSessionRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ShotRepository by delegate {
    private val shots = MutableStateFlow(delegate.history.value)
    private val latest = MutableStateFlow(delegate.latestShot.value)

    override val history: StateFlow<List<ShotEvent>> = shots
    override val latestShot: StateFlow<ShotEvent?> = latest

    override fun deleteShot(eventId: String) = update { it.eventId != eventId }

    override fun deleteShotByTimestamp(timestamp: String) {
        val connected = connectedPi()
        if (connected == null) {
            update { it.timestamp != timestamp }
        } else {
            scope.launch { connected.deleteShot(timestamp) }
        }
    }

    override fun clearHistory() {
        val connected = connectedPi()
        val profileId =
            connected
                ?.profiles
                ?.value
                ?.activeProfileId
                .orEmpty()
        if (connected == null || profileId.isEmpty()) {
            update { false }
        } else {
            scope.launch { connected.clearSession(profileId) }
        }
    }

    private fun connectedPi(): PiSessionRepository? = pi?.takeIf { it.linkState.value == PiLinkState.Connected }

    private fun update(keep: (ShotEvent) -> Boolean) {
        shots.value = shots.value.filter(keep)
        latest.value = shots.value.firstOrNull()
    }
}
