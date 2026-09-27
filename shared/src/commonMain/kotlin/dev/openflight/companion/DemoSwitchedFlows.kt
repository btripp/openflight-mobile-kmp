// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Plan F14: [real]'s value while [useDemo] is `false`, [demo]'s while it's `true`. Synchronous
 * ([value] reads the side that's live right now) and scope-free: nothing is collected until someone
 * collects this, so building one opens nothing. Collectors see the other side's current value at
 * once when the mode flips, like any [StateFlow] update.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class DemoSwitchedStateFlow<T>(
    private val useDemo: StateFlow<Boolean>,
    private val real: StateFlow<T>,
    private val demo: StateFlow<T>,
) : StateFlow<T> {
    override val value: T
        get() = if (useDemo.value) demo.value else real.value

    override val replayCache: List<T>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        useDemo.flatMapLatest { if (it) demo else real }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

/** [DemoSwitchedStateFlow] for a one-shot [SharedFlow] (no replay): events from the live side only. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class DemoSwitchedSharedFlow<T>(
    private val useDemo: StateFlow<Boolean>,
    private val real: SharedFlow<T>,
    private val demo: SharedFlow<T>,
) : SharedFlow<T> {
    override val replayCache: List<T>
        get() = emptyList()

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        useDemo.flatMapLatest { if (it) demo else real }.collect(collector)
        awaitCancellation()
    }
}

/** [real] while [useDemo] is `false`, [demo] while it's `true`, re-subscribing when it flips. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> demoSwitched(
    useDemo: StateFlow<Boolean>,
    real: () -> Flow<T>,
    demo: () -> Flow<T>,
): Flow<T> = useDemo.flatMapLatest { if (it) demo() else real() }
