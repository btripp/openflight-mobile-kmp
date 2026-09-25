// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The shutdown phase machine (plan R8f, Expo `device.tsx` `ShutdownSection` and
 * `__tests__/shutdown.test.ts`): `idle → confirming → pending → done | failed`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsShutdownTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "192.168.1.100:8080")
    private val shots = FakeShotRepository()
    private val piSession = FakePiSessionRepository()
    private val lifecycle = AppLifecycle().apply { onForeground() }
    private lateinit var viewModel: SettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SettingsViewModel(shots, settings, piSession, lifecycle)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aConfirmedStopIsPendingUntilTheServerAcceptsIt() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<Unit>()
            shots.shutdownResponse = { answer.await() }
            viewModel.shutdownPhases().test {
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)
                viewModel.onEvent(SettingsEvent.RequestShutdown)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Confirming)

                viewModel.onEvent(SettingsEvent.ConfirmShutdown)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Pending("192.168.1.100:8080"))

                answer.complete(Unit)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Done("192.168.1.100:8080"))
            }
        }

    @Test
    fun theDropThatFollowsASuccessfulStopIsTheExpectedOutcome() =
        runTest(dispatcher) {
            viewModel.shutdownPhases().test {
                confirm()
                assertThat(awaitUntil { it is ShutdownPhase.Done }).isEqualTo(ShutdownPhase.Done("192.168.1.100:8080"))

                // The server exits ~0.5 s after answering, taking the link with it.
                piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "closed")
                expectNoEvents()
                assertThat(viewModel.uiState.value.shutdown.phase)
                    .isEqualTo(ShutdownPhase.Done("192.168.1.100:8080"))
            }
        }

    @Test
    fun anOutcomeIsDroppedOnceANewConnectionComesUp() =
        runTest(dispatcher) {
            viewModel.shutdownPhases().test {
                confirm()
                awaitUntil { it is ShutdownPhase.Done }
                piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "closed")

                piSession.linkState.value = PiLinkState.Connected
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)
            }
        }

    @Test
    fun aRefusalFailsWithTheServersReasonAndARetryGoesToTheCapturedTarget() =
        runTest(dispatcher) {
            var attempts = 0
            shots.shutdownResponse = {
                attempts++
                if (attempts == 1) error("Shutdown request failed (500)")
            }
            viewModel.shutdownPhases().test {
                confirm()
                assertThat(awaitUntil { it is ShutdownPhase.Failed })
                    .isEqualTo(ShutdownPhase.Failed("192.168.1.100:8080", "Shutdown request failed (500)"))

                // The user switched Pis meanwhile: the retry must not stop one nobody confirmed.
                settings.setHost("other.local:8080")
                viewModel.onEvent(SettingsEvent.RetryShutdown)
                assertThat(awaitUntil { it is ShutdownPhase.Done }).isEqualTo(ShutdownPhase.Done("192.168.1.100:8080"))
            }
            assertThat(shots.shutdownTargets).containsExactly("192.168.1.100:8080", "192.168.1.100:8080")
        }

    @Test
    fun aPiThatNeverAnswersFailsAfterTenSeconds() =
        runTest(dispatcher) {
            shots.shutdownResponse = { awaitCancellation() }
            viewModel.shutdownPhases().test {
                confirm()
                awaitUntil { it is ShutdownPhase.Pending }

                advanceTimeBy(ShutdownPhase.TIMEOUT_MILLIS - 1)
                expectNoEvents()
                advanceTimeBy(2)
                assertThat(awaitItem())
                    .isEqualTo(ShutdownPhase.Failed("192.168.1.100:8080", ShutdownPhase.TIMED_OUT))
            }
        }

    @Test
    fun goingToTheBackgroundFailsAPendingStopWithConnectionDropped() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<Unit>()
            shots.shutdownResponse = { answer.await() }
            viewModel.shutdownPhases().test {
                confirm()
                awaitUntil { it is ShutdownPhase.Pending }

                lifecycle.onBackground()
                assertThat(awaitItem())
                    .isEqualTo(ShutdownPhase.Failed("192.168.1.100:8080", ShutdownPhase.CONNECTION_DROPPED))

                // A late answer doesn't overturn it.
                answer.complete(Unit)
                expectNoEvents()
            }
        }

    @Test
    fun aConfirmationCollapsesWhenTheLinkDropsOrTheAppBackgrounds() =
        runTest(dispatcher) {
            viewModel.shutdownPhases().test {
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)
                viewModel.onEvent(SettingsEvent.RequestShutdown)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Confirming)
                piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "closed")
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)

                piSession.linkState.value = PiLinkState.Connected
                viewModel.onEvent(SettingsEvent.RequestShutdown)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Confirming)
                lifecycle.onBackground()
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)
            }
            assertThat(shots.shutdownTargets).containsExactly()
        }

    @Test
    fun dismissingAnOutcomeReturnsToIdle() =
        runTest(dispatcher) {
            viewModel.shutdownPhases().test {
                confirm()
                awaitUntil { it is ShutdownPhase.Done }

                viewModel.onEvent(SettingsEvent.DismissShutdown)
                assertThat(awaitItem()).isEqualTo(ShutdownPhase.Idle)
            }
        }

    private fun confirm() {
        viewModel.onEvent(SettingsEvent.RequestShutdown)
        viewModel.onEvent(SettingsEvent.ConfirmShutdown)
    }

    private fun SettingsViewModel.shutdownPhases() = uiState.map { it.shutdown.phase }.distinctUntilChanged()

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
