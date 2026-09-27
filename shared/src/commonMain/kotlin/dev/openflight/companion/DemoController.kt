// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.DemoModeRepository
import dev.openflight.companion.core.data.DemoSeedSession
import dev.openflight.companion.core.data.DemoShotHistoryRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.flight.DemoShotGenerator
import dev.openflight.companion.core.model.pi.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Plan F14: Demo mode itself ([DemoModeRepository]) and the switch the app's Pi-facing
 * repositories follow ([DemoSwitch]). It owns the pretend Pi ([pi], [shots]) and Demo mode's
 * history ([history]).
 *
 * - The mode is held in memory and only persisted ([SettingsRepository.demoMode]); the persisted
 *   choice is read once, the first time the app connects ([whenRestored]), with a one-shot read,
 *   never a standing DataStore collector.
 * - Turning it on starts a fresh live demo session, seeds a few past sessions the first time, and
 *   (with [autoFireSeconds]) hits a shot every few seconds. Turning it off stops the pretend Pi;
 *   [DemoSwitchingShotRepository] reconnects the real one.
 *
 * Mode changes ([setEnabled], and the restore) run on [mainScope], the thread the repositories'
 * lifecycle calls are made on; the pretend Pi runs on [workScope].
 */
@Suppress("LongParameterList") // Collaborators and test seams.
internal class DemoController(
    private val settings: SettingsRepository,
    private val history: DemoShotHistoryRepository,
    private val mainScope: CoroutineScope,
    private val workScope: CoroutineScope,
    private val clock: () -> Long,
    seed: Long = clock(),
    timing: DemoTiming = DemoTiming(),
    offsetMillis: (Long) -> Long = ::localUtcOffsetMillis,
) : DemoModeRepository,
    DemoSwitch {
    val pi = DemoPiSessionRepository(workScope, timing)
    val shots = DemoShotRepository(settings, pi, history, workScope, seed, clock, timing, offsetMillis)

    private val mode = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = mode.asStateFlow()
    override val useDemo: StateFlow<Boolean> get() = enabled

    private val autoFire = MutableStateFlow(DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS)
    override val autoFireSeconds: StateFlow<Int> = autoFire.asStateFlow()

    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private val afterRestore = mutableListOf<() -> Unit>()
    private var restored = false
    private var restoring: Job? = null
    private var autoFireJob: Job? = null

    override fun onModeChange(listener: (demo: Boolean) -> Unit) {
        listeners += listener
    }

    override fun whenRestored(action: () -> Unit) {
        if (restored) {
            action()
            return
        }
        afterRestore += action
        if (restoring == null) {
            restoring =
                mainScope.launch {
                    val persisted = settings.demoMode.first()
                    autoFire.value = settings.demoAutoFireSeconds.first()
                    finishRestore(persisted)
                }
        }
    }

    private fun finishRestore(persisted: Boolean?) {
        if (!restored) {
            restored = true
            if (persisted != null) apply(persisted)
        }
        val actions = afterRestore.toList()
        afterRestore.clear()
        actions.forEach { it() }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        // An explicit choice wins over a restore still reading the stored one.
        restoring?.cancel()
        restored = true
        apply(enabled)
        // Anything that was waiting for the stored choice (the first connect) goes ahead now.
        finishRestore(persisted = null)
        settings.setDemoMode(enabled)
    }

    private fun apply(on: Boolean) {
        if (mode.value == on) return
        // A fresh live session each time, so nothing already seen is reported again.
        if (on) shots.reset()
        mode.value = on
        listeners.forEach { it(on) }
        if (on) {
            workScope.launch { history.seedIfEmpty(DemoHistorySeed.sessions(clock(), pi.profiles.value.profiles)) }
        }
        restartAutoFire()
    }

    override suspend fun setAutoFireSeconds(seconds: Int) {
        if (seconds !in DemoModeRepository.AUTO_FIRE_OPTIONS) return
        autoFire.value = seconds
        restartAutoFire()
        settings.setDemoAutoFireSeconds(seconds)
    }

    private fun restartAutoFire() {
        autoFireJob?.cancel()
        val seconds = autoFire.value
        if (!mode.value || seconds <= 0) return
        autoFireJob =
            workScope.launch {
                while (isActive) {
                    delay(seconds * MILLIS_PER_SECOND)
                    shots.hit()
                }
            }
    }

    override suspend fun hitShot() {
        if (mode.value) workScope.launch { shots.hit() }
    }

    override suspend fun clearDemoData() {
        history.clearAll()
        shots.reset()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Plan F14: the past sessions Demo mode starts with, so Sessions, Bag and gapping, replay, the
 * overlay and View on range have something to show: four sessions over the last week across the
 * pretend Pi's profiles and most of a bag, from a fixed seed (the same history every time).
 */
@OptIn(ExperimentalUuidApi::class)
internal object DemoHistorySeed {
    private const val SEED = 20_260_927L
    private const val DAY_MILLIS = 86_400_000L
    private const val SECONDS_BETWEEN_SHOTS = 45L
    private const val MILLIS_PER_SECOND = 1_000L

    /** One seeded session: how many days ago, and each profile index's clubs with their counts. */
    private data class Plan(
        val daysAgo: Int,
        val title: String,
        val swings: List<Triple<Int, String, Int>>,
    )

    @Suppress("MagicNumber") // A made-up practice week.
    private val PLANS =
        listOf(
            Plan(
                daysAgo = 6,
                title = "Driver and irons",
                swings = listOf(Triple(0, "driver", 8), Triple(0, "7-iron", 8), Triple(0, "pw", 6)),
            ),
            Plan(
                daysAgo = 4,
                title = "Wedges",
                swings = listOf(Triple(1, "pw", 6), Triple(1, "gw", 5), Triple(1, "sw", 5), Triple(1, "9-iron", 5)),
            ),
            Plan(
                daysAgo = 2,
                title = "Long game",
                swings =
                    listOf(
                        Triple(0, "3-wood", 5),
                        Triple(0, "3-hybrid", 5),
                        Triple(0, "5-iron", 6),
                        Triple(2, "driver", 6),
                    ),
            ),
            Plan(
                daysAgo = 1,
                title = "Full bag",
                swings =
                    listOf(
                        Triple(0, "driver", 5),
                        Triple(0, "6-iron", 5),
                        Triple(0, "8-iron", 5),
                        Triple(0, "9-iron", 5),
                        Triple(1, "7-iron", 6),
                        Triple(2, "pw", 5),
                    ),
            ),
        )

    /** The seeded sessions for "now" = [nowEpochMillis], with shots filed under [profiles]. */
    fun sessions(
        nowEpochMillis: Long,
        profiles: List<Profile>,
        offsetMillis: (Long) -> Long = ::localUtcOffsetMillis,
    ): List<DemoSeedSession> {
        val generator = DemoShotGenerator(SEED)
        return PLANS.map { plan ->
            val start = nowEpochMillis - plan.daysAgo * DAY_MILLIS
            var number = 0
            val shots =
                plan.swings.flatMap { (profileIndex, club, count) ->
                    val profile = profiles.getOrNull(profileIndex)
                    List(count) {
                        number += 1
                        val identity =
                            DemoShotIdentity(
                                eventId = Uuid.random().toString(),
                                timestamp =
                                    naiveLocalTimestamp(
                                        start + number * SECONDS_BETWEEN_SHOTS * MILLIS_PER_SECOND,
                                        offsetMillis,
                                    ),
                                shotNumber = number,
                                profile = profile,
                            )
                        DemoShots.detail(generator.next(club), identity)
                    }
                }
            DemoSeedSession(startedAtEpochMillis = start, shots = shots.reversed(), title = plan.title)
        }
    }
}
