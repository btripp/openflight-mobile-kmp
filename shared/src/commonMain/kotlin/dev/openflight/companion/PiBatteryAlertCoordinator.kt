// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.model.pi.PiBatteryAlertGate
import dev.openflight.companion.core.model.pi.PiBatteryWarning
import dev.openflight.companion.core.speech.ScreenReaderMonitor
import dev.openflight.companion.core.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Speaks a low- or critical-battery warning for the Pi (issue #48), app-scoped like
 * [ShotCalloutCoordinator]: started once ([piBatteryAlerts]), not from any one screen, so the
 * warning reaches a user who's swinging rather than looking at the phone.
 * The Dashboard and the range show the same [PiBatteryWarning] as a notice.
 *
 * Collects [PiSessionRepository.powerStatus] (a snapshot every 5 s), maps it with
 * [PiBatteryWarning.of] and asks [PiBatteryAlertGate] whether this snapshot is a new, lower level.
 * The gate advances on every snapshot, spoken or not, so turning call-outs on later doesn't replay
 * a level the user was already warned of on screen. For a new level:
 * - silent when [SettingsRepository.calloutsEnabled] is off (the app's one "talk to me" switch);
 * - silent while [screenReader] reports a screen reader active (plan A11y: never talk over
 *   TalkBack/VoiceOver, which reads the on-screen notice instead);
 * - otherwise stops any utterance in flight and speaks [PiBatteryWarning.spokenText] with the
 *   call-out voice and rate.
 *
 * [SettingsRepository.calloutTrigger] is deliberately ignored: [CalloutTrigger.GAMES_ONLY] is about
 * which *shots* are worth announcing, and a dying Pi ends every session, game or not.
 *
 * It doesn't follow the app lifecycle itself: [ShotCalloutCoordinator] already stops the shared
 * [SpeechEngine] when the app goes to the background, which cuts this utterance off too.
 */
class PiBatteryAlertCoordinator(
    private val piSession: PiSessionRepository,
    private val settings: SettingsRepository,
    private val speech: SpeechEngine,
    private val screenReader: ScreenReaderMonitor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null
    private val gate = PiBatteryAlertGate()

    /** Starts collecting [PiSessionRepository.powerStatus]. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                piSession.powerStatus.collect { status -> onWarning(PiBatteryWarning.of(status)) }
            }
    }

    private suspend fun onWarning(warning: PiBatteryWarning?) {
        val level = gate.next(warning)
        if (warning == null || level == null) return
        if (!settings.calloutsEnabled.first() || screenReader.isActive.value) return

        speech.stop()
        speech.speak(
            text = warning.spokenText,
            voiceId = settings.calloutVoiceId.first(),
            rate = settings.calloutRate.first(),
        )
    }
}

/**
 * [PiBatteryAlertCoordinator]'s Koin binding, one of [appModules]. Here rather than in `AppKoin.kt`
 * to keep that file's function count down; a function for the same initialization-order reason as
 * `AppKoin.kt`'s `calloutModule()`.
 */
internal fun batteryAlertModule(): Module =
    module {
        single {
            PiBatteryAlertCoordinator(
                piSession = get<PiSessionRepository>(),
                settings = get<SettingsRepository>(),
                speech = get<SpeechEngine>(),
                screenReader = get<ScreenReaderMonitor>(),
            )
        }
    }

/**
 * The app-wide [PiBatteryAlertCoordinator], started (idempotent) the first time this is called.
 * Call once per process, next to [shotCallouts] (Android starts both from the app shell's
 * composition; iOS from `iOSApp.init`), after [applyLaunchOptions] so it follows whichever
 * [PiSessionRepository] the graph holds by then.
 */
fun Koin.piBatteryAlerts(): PiBatteryAlertCoordinator {
    val coordinator = get<PiBatteryAlertCoordinator>()
    coordinator.start()
    return coordinator
}
