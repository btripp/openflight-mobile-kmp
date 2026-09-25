// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The once-per-launch club confirmation (plan R8d): after the first connection of this process the
 * dashboard asks whether the Pi's club is right, because every shot is filed under it and the Pi
 * keeps the club from its last session. Reconnects never reopen it. In memory only (a Koin single,
 * one per process), never persisted, so the next launch asks again.
 */
class ClubConfirmation {
    private val mutablePhase = MutableStateFlow(Phase.WAITING_FOR_CONNECTION)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    /** The first connection of the launch opens it; later ones change nothing. */
    fun onConnected() {
        mutablePhase.update { if (it == Phase.WAITING_FOR_CONNECTION) Phase.SHOWING else it }
    }

    /** Confirmed, or answered by picking a club: done for this launch. */
    fun dismiss() {
        mutablePhase.value = Phase.DONE
    }

    enum class Phase {
        WAITING_FOR_CONNECTION,
        SHOWING,
        DONE,
    }
}
