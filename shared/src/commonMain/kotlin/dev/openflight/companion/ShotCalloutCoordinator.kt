// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.ActiveGameRepository
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.AppLifecycleState
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.ConditionsRepository
import dev.openflight.companion.core.data.FinalShotStream
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.flight.ShotDistanceEstimate
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.insights.CalloutComposer
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.CalloutInput
import dev.openflight.companion.core.model.CalloutContext
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.speech.ScreenReaderMonitor
import dev.openflight.companion.core.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Speaks a shot call-out for every final shot, app-scoped (plan F-series §1 "Call-out
 * orchestration"; F7). Started once, from [initKoin] / the app lifecycle (see `AppKoin.kt`'s
 * `Koin.shotCallouts()`), not from any one screen: a call-out should keep speaking on the
 * dashboard, in a game, wherever the user is.
 *
 * Reads [FinalShotStream.finalShots] (never a raw shot list: F3/A2's stream already dedupes by
 * `event_id` and only emits a shot once it's final). For each shot:
 * - silent when [SettingsRepository.calloutsEnabled] is off;
 * - silent when [SettingsRepository.calloutTrigger] is [CalloutTrigger.GAMES_ONLY] and
 *   [ActiveGameRepository.activeGame] is `null` (no game running);
 * - otherwise composes [SettingsRepository.calloutFields] with [CalloutComposer] and speaks
 *   through [SpeechEngine], stopping any utterance still in flight first (a newer shot always
 *   wins over a stale one);
 * - silent while [screenReader] reports a screen reader active (plan A11y: never talk over
 *   TalkBack/VoiceOver — see [ScreenReaderMonitor]'s doc for why this is a poll-tolerant skip,
 *   not a queued delay).
 *
 * [CalloutField.TOTAL] has no raw wire field (F2's roll estimate is app-side only), so
 * [ShotDistanceEstimator] only runs when the field list actually needs it: every other field
 * reads straight off [ShotEvent]. [CalloutInput.estimated] then reflects which of the requested
 * fields the estimator (or the resolver it wraps) actually had to fill in or adjust, so
 * `CalloutComposer` prefixes only those with "about" (plan §0.2: estimated numbers carry
 * provenance end to end).
 *
 * A defensive `event_id` set (not just [FinalShotStream]'s own dedup) is what makes "a duplicate
 * `event_id` is spoken once" true even for a stream that replays one, e.g. a test double.
 *
 * [conditions] is [Lazy][kotlin.Lazy], not resolved eagerly: `ConditionsRepository`'s real,
 * DataStore-backed implementation starts collecting the settings file the moment it's
 * constructed (`SharingStarted.Eagerly`), so building it merely because this coordinator was
 * constructed — before any shot ever asks for [CalloutField.TOTAL] — would open that file even
 * for a user who never turns call-outs on, and would collide with a second instance the next time
 * a test (or a debug launch hook) restarts the Koin graph in the same process.
 */
@Suppress("LongParameterList") // Every collaborator is a distinct, independently-fakeable dependency; see the tests.
class ShotCalloutCoordinator(
    private val finalShots: FinalShotStream,
    private val settings: SettingsRepository,
    private val activeGame: ActiveGameRepository,
    private val conditions: Lazy<ConditionsRepository>,
    private val speech: SpeechEngine,
    private val screenReader: ScreenReaderMonitor,
    private val lifecycle: AppLifecycle,
    private val estimator: ShotDistanceEstimator = ShotDistanceEstimator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null
    private val spoken = mutableSetOf<String>()

    /** Starts collecting [finalShots] and following [lifecycle]. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch { followLifecycle() }
                finalShots.finalShots().collect { shot -> onFinalShot(shot) }
            }
    }

    /** Cuts off a call-out in flight the moment the app leaves the foreground. */
    private suspend fun followLifecycle() {
        lifecycle.state.collect { state -> if (state == AppLifecycleState.BACKGROUND) speech.stop() }
    }

    @Suppress("ReturnCount") // A guard-clause ladder: each gate is a distinct, independently-testable silence rule.
    private suspend fun onFinalShot(shot: ShotEvent) {
        if (!spoken.add(shot.eventId)) return
        if (!settings.calloutsEnabled.first()) return

        val context = activeGame.activeGame.value
        if (settings.calloutTrigger.first() == CalloutTrigger.GAMES_ONLY && context == null) return

        val fields = settings.calloutFields.first()
        if (fields.isEmpty()) return
        if (screenReader.isActive.value) return

        val text = compose(shot, fields, context)
        if (text.isEmpty()) return

        speech.stop()
        speech.speak(text = text, voiceId = settings.calloutVoiceId.first(), rate = settings.calloutRate.first())
    }

    private suspend fun compose(
        shot: ShotEvent,
        fields: List<CalloutField>,
        context: CalloutContext?,
    ): String {
        val units = settings.units.first()
        val estimate = if (CalloutField.TOTAL in fields) estimate(shot) else null
        val input = buildInput(shot, estimate, context)
        return CalloutComposer.compose(input, fields, units)
    }

    private fun estimate(shot: ShotEvent): ShotDistanceEstimate? {
        val repository = conditions.value
        return estimator.estimate(
            shot.toFlightMeasurements(),
            repository.conditions.value,
            repository.targetBearing.value,
        )
    }

    private fun buildInput(
        shot: ShotEvent,
        estimate: ShotDistanceEstimate?,
        context: CalloutContext?,
    ): CalloutInput {
        val carry = estimate?.carryYards ?: shot.estimatedCarryYards
        val targetDelta = context?.targetYards?.let { target -> carry - target }

        val estimated = mutableSetOf<CalloutField>()
        if (estimate != null) {
            if (estimate.isAdjusted) estimated += CalloutField.CARRY
            estimated += CalloutField.TOTAL
        }

        return CalloutInput(
            carryYards = carry,
            totalYards = estimate?.totalYards,
            ballSpeedMph = shot.ballSpeedMph,
            clubSpeedMph = shot.clubSpeedMph,
            smash = shot.smashFactor,
            launchAngleDegrees = shot.launchAngleVertical,
            spinRpm = shot.spinRpm,
            club = GolfClub.fromWireValue(shot.club),
            targetDeltaYards = targetDelta,
            estimated = estimated,
        )
    }
}
