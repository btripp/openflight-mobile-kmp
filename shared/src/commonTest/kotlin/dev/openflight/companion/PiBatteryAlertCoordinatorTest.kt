// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.model.pi.PiBatteryWarning
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeScreenReaderMonitor
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [PiBatteryAlertCoordinator] wiring (issue #48): which power snapshots get spoken. When a status
 * warrants a warning, and its wording, are [PiBatteryWarning]'s own tested job; the once-per-level
 * rule is [dev.openflight.companion.core.model.pi.PiBatteryAlertGate]'s.
 */
class PiBatteryAlertCoordinatorTest {
    private class Harness(
        scope: TestScope,
    ) {
        val piSession = FakePiSessionRepository()
        val settings = FakeSettingsRepository()
        val speech = FakeSpeechEngine()
        val screenReader = FakeScreenReaderMonitor()
        val coordinator =
            PiBatteryAlertCoordinator(
                piSession = piSession,
                settings = settings,
                speech = speech,
                screenReader = screenReader,
                scope = scope.backgroundScope,
            )

        private var snapshot = 0

        /** A fresh 5 s snapshot (a new `updated_at`, so the StateFlow emits it even when unchanged). */
        fun report(
            state: PowerState,
            percent: Double?,
            externalPower: Boolean = false,
        ) {
            snapshot++
            piSession.powerStatus.value =
                PowerStatus(
                    available = true,
                    provider = "geekworm",
                    state = state,
                    batteryPercent = percent,
                    externalPower = externalPower,
                    updatedAt = "2026-09-28T12:00:${snapshot.toString().padStart(2, '0')}Z",
                )
        }

        val spokenTexts: List<String> get() = speech.spoken.map { it.text }
    }

    private val lowText = "OpenFlight battery low, 18 percent. Connect external power soon."
    private val criticalText = "OpenFlight battery critical, 9 percent. Connect external power now."

    @Test
    fun aLowBatterySpeaksOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.coordinator.start()

            h.report(PowerState.ON_BATTERY, 45.0)
            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText)
        }

    @Test
    fun repeatedSnapshotsAtOneLevelDoNotRepeatIt() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.coordinator.start()

            repeat(4) { h.report(PowerState.LOW, 18.0) }
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText)
        }

    @Test
    fun droppingToCriticalSpeaksOnceMore() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.coordinator.start()

            h.report(PowerState.LOW, 18.0)
            h.report(PowerState.CRITICAL, 9.0)
            h.report(PowerState.CRITICAL, 9.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText, criticalText)
        }

    @Test
    fun itStopsAnUtteranceInFlightAndUsesTheCalloutVoiceAndRate() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.settings.setCalloutVoiceId("voice-1")
            h.settings.setCalloutRate(1.25f)
            h.coordinator.start()

            h.report(PowerState.CRITICAL, 9.0)
            testScheduler.runCurrent()

            val utterance = h.speech.spoken.single()
            assertThat(utterance.voiceId).isEqualTo("voice-1")
            assertThat(utterance.rate).isEqualTo(1.25f)
            assertThat(h.speech.stopCalls >= 1).isTrue()
        }

    @Test
    fun calloutsOffStaysSilentAndDoesNotReplayTheLevelWhenTurnedOn() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.coordinator.start()

            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()
            assertThat(h.spokenTexts).isEmpty()

            h.settings.setCalloutsEnabled(true)
            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()
            assertThat(h.spokenTexts).isEmpty()

            // A new, lower level still speaks.
            h.report(PowerState.CRITICAL, 9.0)
            testScheduler.runCurrent()
            assertThat(h.spokenTexts).containsExactly(criticalText)
        }

    @Test
    fun aScreenReaderActiveKeepsItSilent() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.screenReader.isActive.value = true
            h.coordinator.start()

            h.report(PowerState.CRITICAL, 9.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).isEmpty()
        }

    @Test
    fun pluggingInReArmsSoALaterDropSpeaksAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.coordinator.start()

            h.report(PowerState.LOW, 18.0)
            h.report(PowerState.LOW, 18.0, externalPower = true)
            h.report(PowerState.PLUGGED_IN, 40.0, externalPower = true)
            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText, lowText)
        }

    @Test
    fun itSpeaksEvenWhenShotCalloutsAreGamesOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.settings.setCalloutTrigger(CalloutTrigger.GAMES_ONLY)
            h.coordinator.start()

            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText)
        }

    @Test
    fun startIsIdempotent() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.settings.setCalloutsEnabled(true)
            h.coordinator.start()
            h.coordinator.start()

            h.report(PowerState.LOW, 18.0)
            testScheduler.runCurrent()

            assertThat(h.spokenTexts).containsExactly(lowText)
        }
}
