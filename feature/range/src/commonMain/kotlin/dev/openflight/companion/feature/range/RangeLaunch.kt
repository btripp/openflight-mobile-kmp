// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import dev.openflight.companion.core.data.HistoryShot

/**
 * Plan F8d: open the range on a stored session ("view on range"), sent as
 * [DrivingRangeEvent.Launch].
 *
 * - With a `null` [shotId] the session replays from its first shot, playing: the same as
 *   [DrivingRangeEvent.StartReplay].
 * - With a [shotId] the replay opens **paused** on that shot and flies it once; next, previous and
 *   play then work as usual.
 *
 * @property sessionId the stored session ([dev.openflight.companion.core.data.HistorySession.id]).
 *   For a shot of the live session, the current history session
 *   ([dev.openflight.companion.core.data.ShotHistoryRepository.currentSessionId]).
 * @property shotId any id a screen already has for the shot: the stored row id
 *   ([HistoryShot.id]), its SSE/BLE event id (or the [historyEventId] a row without one flies
 *   under), or the Pi's timestamp (the key Pi-sourced rows use). When the shot isn't in
 *   [sessionId] (a Pi session outlives the phone's history session), every stored session is
 *   searched, newest first.
 */
data class RangeLaunch(
    val sessionId: String,
    val shotId: String? = null,
)

/**
 * The index in these stored shots of the one [shotId] names (see [RangeLaunch.shotId]), or -1.
 * The row id wins over the event id, and the event id over the timestamp, so an id is never
 * mistaken for another kind.
 */
fun List<HistoryShot>.indexOfLaunchShot(shotId: String): Int =
    sequenceOf<(HistoryShot) -> Boolean>(
        { it.id.toString() == shotId },
        {
            it.eventId.equals(shotId, ignoreCase = true) ||
                it.toRangeShotEvent()?.eventId.equals(shotId, ignoreCase = true)
        },
        { it.detail.timestamp == shotId },
    ).map(::indexOfFirst).firstOrNull { it >= 0 } ?: -1
