// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * New shots as events rather than a list (plan F3, A2), for consumers that act once per shot:
 * the call-outs and the games.
 *
 * Both flows are derived from [ShotRepository.history] by diffing each snapshot against what the
 * collector has already seen, so each collector starts from the history as it is when it starts
 * collecting (those shots are not replayed) and emits in arrival order (oldest first).
 */
interface FinalShotStream {
    /**
     * Each shot once, when it becomes final: a v2 `final: true` shot (which replaces its
     * provisional version in the history, same `event_id`), or a v1/BLE-v1 shot (`final == null`),
     * which is final as it arrives. A provisional shot (`final == false`) is not emitted until its
     * final version arrives. A shot that was already in the history but still provisional when
     * collection started is emitted when it turns final.
     */
    fun finalShots(): Flow<ShotEvent>

    /**
     * Each shot once, the first time its `event_id` appears, provisional or not: the moment a
     * swing is seen, for attributing it to whoever's turn it is (A14).
     */
    fun firstSightings(): Flow<ShotEvent>
}

/** [FinalShotStream] over a shot history flow, newest first ([ShotRepository.history]). */
class DefaultFinalShotStream(
    private val history: StateFlow<List<ShotEvent>>,
) : FinalShotStream {
    override fun finalShots(): Flow<ShotEvent> =
        flow {
            var seenFinal: MutableSet<String>? = null
            history.collect { shots ->
                val known = seenFinal
                if (known == null) {
                    seenFinal = shots.filter { it.isFinal }.mapTo(mutableSetOf()) { it.eventId }
                } else {
                    shots.asReversed().forEach { shot ->
                        if (shot.isFinal && known.add(shot.eventId)) emit(shot)
                    }
                }
            }
        }

    override fun firstSightings(): Flow<ShotEvent> =
        flow {
            var seen: MutableSet<String>? = null
            history.collect { shots ->
                val known = seen
                if (known == null) {
                    seen = shots.mapTo(mutableSetOf()) { it.eventId }
                } else {
                    shots.asReversed().forEach { shot ->
                        if (known.add(shot.eventId)) emit(shot)
                    }
                }
            }
        }

    private companion object {
        /** "Final" is `final != false`: v1 shots carry no flag and are final as they arrive. */
        val ShotEvent.isFinal: Boolean get() = final != false
    }
}
