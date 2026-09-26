// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.DefaultActiveGameRepository
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.model.CalloutContext
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakeFinalShotStream
import dev.openflight.companion.core.testing.FakeScreenReaderMonitor
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [ShotCalloutCoordinator] wiring (plan F7): the coordinator's own job is picking whether to
 * speak and what to build the call-out from, not the physics or the composed wording — those are
 * [dev.openflight.companion.core.flight.ShotDistanceEstimator] and
 * [dev.openflight.companion.core.insights.CalloutComposer]'s own tested job.
 */
class ShotCalloutCoordinatorTest {
    private class Harness(
        scope: TestScope,
    ) {
        val finalShots = FakeFinalShotStream()
        val settings = FakeSettingsRepository()
        val activeGame = DefaultActiveGameRepository()
        val conditions = FakeConditionsRepository()
        val speech = FakeSpeechEngine()
        val screenReader = FakeScreenReaderMonitor()
        val lifecycle = AppLifecycle()
        val coordinator =
            ShotCalloutCoordinator(
                finalShots = finalShots,
                settings = settings,
                activeGame = activeGame,
                conditions = lazy { conditions },
                speech = speech,
                screenReader = screenReader,
                lifecycle = lifecycle,
                scope = scope.backgroundScope,
            )

        suspend fun enable(fields: List<CalloutField> = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED)) {
            settings.setCalloutsEnabled(true)
            settings.setCalloutFields(fields)
        }
    }

    private fun shot(
        number: Int,
        club: String = "driver",
    ) = ShotEvent(
        schemaVersion = 1,
        eventId = shotId(number),
        timestamp = "2026-08-05T23:54:00",
        club = club,
        ballSpeedMph = 140.0,
        estimatedCarryYards = 250.0,
    )

    private fun shotId(number: Int) = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')

    @Test
    fun givenCalloutsDisabled_whenAFinalShotArrives_thenNothingIsSpoken() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken).isEmpty()
        }

    @Test
    fun givenCalloutsEnabled_whenAFinalShotArrives_thenTheEngineSpeaksTheComposedText() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken.map { it.text }).contains("250 yards, 140 miles per hour")
        }

    @Test
    fun aProvisionalShotIsNotSpokenOnlyAFinalOneIs() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.coordinator.start()

            // The coordinator only ever collects finalShots(), never firstSightings(): a
            // provisional shot has no way to reach it before it turns final.
            h.finalShots.emitFirstSighting(shot(1))
            testScheduler.runCurrent()
            assertThat(h.speech.spoken).isEmpty()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()
            assertThat(h.speech.spoken.size).isEqualTo(1)
        }

    @Test
    fun aDuplicateEventIdIsSpokenOnlyOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken.size).isEqualTo(1)
        }

    @Test
    fun gamesOnlyStaysSilentOutsideAGame() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.settings.setCalloutTrigger(CalloutTrigger.GAMES_ONLY)
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()
            assertThat(h.speech.spoken).isEmpty()

            h.activeGame.set(CalloutContext(gameTitle = "Target call-out", targetYards = 150.0))
            h.finalShots.emitFinal(shot(2))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken.size).isEqualTo(1)
        }

    @Test
    fun aNewerShotStopsAStaleUtteranceBeforeSpeaking() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            h.finalShots.emitFinal(shot(2))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken.size).isEqualTo(2)
            assertThat(h.speech.stopCalls >= 1).isTrue()
        }

    @Test
    fun goingToTheBackgroundStopsAnInFlightUtterance() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.coordinator.start()

            h.lifecycle.onForeground()
            h.lifecycle.onBackground()
            testScheduler.runCurrent()

            assertThat(h.speech.stopCalls >= 1).isTrue()
        }

    @Test
    fun aScreenReaderActiveKeepsTheCoordinatorSilent() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable()
            h.screenReader.isActive.value = true
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            assertThat(h.speech.spoken).isEmpty()
        }

    @Test
    fun totalRequiresTheDistanceEstimatorAndIsMarkedEstimated() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable(fields = listOf(CalloutField.TOTAL))
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            val text =
                h.speech.spoken
                    .single()
                    .text
            assertThat(text).startsWith("about ")
        }

    @Test
    fun nonIsaConditionsMarkCarryAsEstimatedWhenTotalIsAlsoRequested() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(this)
            h.enable(fields = listOf(CalloutField.CARRY, CalloutField.TOTAL))
            h.conditions.conditions.value = Conditions.ISA.copy(altitudeMeters = 1609.0)
            h.coordinator.start()

            h.finalShots.emitFinal(shot(1))
            testScheduler.runCurrent()

            val text =
                h.speech.spoken
                    .single()
                    .text
            // Both requested fields came from the estimator once density differs from ISA.
            assertThat(text.split(", ").all { it.startsWith("about ") }).isTrue()
        }
}
