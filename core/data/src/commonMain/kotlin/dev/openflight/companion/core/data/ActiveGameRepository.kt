// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.CalloutContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The game in progress, if any (plan F3, A2): the games screen [set]s it while a game runs and
 * [clear]s it when the game ends or its screen goes away; the shot call-outs read [activeGame]
 * for the target and player. In memory only: a game doesn't outlive the app.
 */
interface ActiveGameRepository {
    /** The running game's call-out context, or `null` outside a game. */
    val activeGame: StateFlow<CalloutContext?>

    fun set(context: CalloutContext)

    fun clear()
}

/** [ActiveGameRepository] over a [MutableStateFlow]. */
class DefaultActiveGameRepository : ActiveGameRepository {
    private val state = MutableStateFlow<CalloutContext?>(null)
    override val activeGame: StateFlow<CalloutContext?> = state.asStateFlow()

    override fun set(context: CalloutContext) {
        state.value = context
    }

    override fun clear() {
        state.value = null
    }
}
