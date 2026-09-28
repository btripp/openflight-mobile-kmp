// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/**
 * Plan F14: Demo mode, for trying the app without a Pi. While it's on, an in-app pretend Pi takes the
 * place of the real one behind the same [ShotRepository], [PiSessionRepository] and
 * [ShotHistoryRepository] every screen already uses, so everything works and nothing needs a
 * relaunch. Its shots are made up and labelled "Demo"; its sessions are kept apart from the
 * player's own ([SessionSource.DEMO]).
 *
 * The app's implementation lives in `shared` (it drives the pretend Pi); features read and flip it
 * through this interface.
 */
interface DemoModeRepository {
    /** Whether Demo mode is on. Starts `false` until the persisted choice is restored. */
    val enabled: StateFlow<Boolean>

    /** Seconds between automatic demo shots while Demo mode is on; 0 is off. One of [AUTO_FIRE_OPTIONS]. */
    val autoFireSeconds: StateFlow<Int>

    /**
     * Turns Demo mode on or off at once (the Pi-facing repositories switch over) and persists the
     * choice. Turning it on connects the pretend Pi; turning it off returns to the real connection.
     */
    suspend fun setEnabled(enabled: Boolean)

    /** Persists the auto-fire interval; a value outside [AUTO_FIRE_OPTIONS] is ignored. */
    suspend fun setAutoFireSeconds(seconds: Int)

    /** "Hit a shot": the pretend Pi reports one new swing with the selected club. Ignored while off. */
    suspend fun hitShot()

    /**
     * "Clear demo data": deletes every demo session and the pretend Pi's current session. The past
     * sessions are seeded again the next time Demo mode turns on.
     */
    suspend fun clearDemoData()

    companion object {
        /** Off, or a demo shot every 5, 10 or 20 seconds. */
        val AUTO_FIRE_OPTIONS: List<Int> = listOf(0, 5, 10, 20)
        const val DEFAULT_AUTO_FIRE_SECONDS: Int = 0

        /** The host Demo mode's sessions are filed under, shown where a real Pi's address would be. */
        const val DEMO_HOST: String = "Demo Pi"
    }
}

/**
 * A Demo mode that is never on and does nothing: the default for ViewModels built without one (their
 * unit tests), so only the app's graph wires the real one in.
 */
object DemoModeOff : DemoModeRepository {
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val autoFireSeconds: StateFlow<Int> =
        MutableStateFlow(DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS).asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun setAutoFireSeconds(seconds: Int) = Unit

    override suspend fun hitShot() = Unit

    override suspend fun clearDemoData() = Unit
}

/**
 * Plan F14: Demo mode's own history, over the same database as the player's: its sessions are
 * stored with `source = 'DEMO'` and only ever listed, counted, deleted or cleared here.
 * [clearAll] is "Clear demo data".
 */
interface DemoShotHistoryRepository : ShotHistoryRepository {
    /**
     * Stores [sessions] as past demo sessions, unless there are demo sessions already.
     *
     * @return whether they were stored.
     */
    suspend fun seedIfEmpty(sessions: List<DemoSeedSession>): Boolean
}

/**
 * One past demo session to seed ([DemoShotHistoryRepository.seedIfEmpty]): its shots, newest first,
 * in the Pi's `shot_to_dict` shape, with their demo profiles.
 */
data class DemoSeedSession(
    val startedAtEpochMillis: Long,
    val shots: List<ShotDetail>,
    val title: String? = null,
)

/**
 * Plan F14: Koin qualifiers for the real, Pi-backed implementations. `dataModule` binds each real
 * [ShotRepository], [PiSessionRepository] and [ShotHistoryRepository] under [Real] (and, unqualified,
 * as the default the app uses), so the app can bind a Demo-mode switch in front of them as the
 * unqualified binding; [DemoHistory] is Demo mode's [DemoShotHistoryRepository].
 */
object DataBindings {
    val Real: Qualifier = named("openflight.real")
    val DemoHistory: Qualifier = named("openflight.demoHistory")
}

/** [DemoShotHistoryRepository] over a demo-world [DefaultShotHistoryRepository]. */
internal class DefaultDemoShotHistoryRepository(
    private val history: DefaultShotHistoryRepository,
) : DemoShotHistoryRepository,
    ShotHistoryRepository by history {
    override suspend fun seedIfEmpty(sessions: List<DemoSeedSession>): Boolean = history.seedDemoIfEmpty(sessions)
}
