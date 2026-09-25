// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ConnectionErrorKind
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ShotProcessingState
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Plan R8f on the live dashboard: the processing indicator and connection problems in words. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardProcessingTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()

    private fun viewModel() = DashboardViewModel(shots, settings, piSession, ClubConfirmation())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun eachProcessingStateHasTheWebUiWording() {
        assertThat(ProcessingIndicator.of(ShotProcessingState.CAPTURING))
            .isEqualTo(ProcessingIndicator(ShotProcessingState.CAPTURING, "Impact detected", "Capturing radar data…"))
        assertThat(ProcessingIndicator.of(ShotProcessingState.CALCULATING))
            .isEqualTo(ProcessingIndicator(ShotProcessingState.CALCULATING, "Shot captured", "Calculating metrics…"))
        assertThat(ProcessingIndicator.of(ShotProcessingState.FAILED)?.failed).isEqualTo(true)
        assertThat(ProcessingIndicator.of(ShotProcessingState.CAPTURING)?.failed).isEqualTo(false)
        assertThat(ProcessingIndicator.of(null)).isNull()
    }

    @Test
    fun theIndicatorFollowsShotProcessingUntilTheNextShotClearsIt() =
        runTest {
            viewModel().uiState.test {
                assertThat(awaitItem().processing).isNull()

                piSession.shotProcessing.value = ShotProcessingState.CAPTURING
                assertThat(awaitUntil { it.processing != null }.processing?.state)
                    .isEqualTo(ShotProcessingState.CAPTURING)
                piSession.shotProcessing.value = ShotProcessingState.CALCULATING
                assertThat(awaitUntil { it.processing?.state == ShotProcessingState.CALCULATING }.processing?.title)
                    .isEqualTo("Shot captured")

                // The repository clears it when the shot arrives.
                piSession.shotProcessing.value = null
                shots.history.value = listOf(shot(1))
                val live = awaitUntil { it is DashboardUiState.Live && it.processing == null }
                assertThat(live.processing).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aFailedCaptureStaysUntilTheNextShotAndNeverFiresTheShotEffect() =
        runTest {
            val vm = viewModel()
            vm.effects.test {
                vm.uiState.test {
                    piSession.shotProcessing.value = ShotProcessingState.FAILED
                    assertThat(awaitUntil { it.processing != null }.processing?.failed).isEqualTo(true)
                    cancelAndIgnoreRemainingEvents()
                }
                expectNoEvents()
            }
        }

    @Test
    fun aRejectedLinkIsSpelledOutOnTheCard() =
        runTest {
            viewModel().uiState.test {
                piSession.linkState.value = PiLinkState.Rejected("Public addresses need HTTPS")
                val panel = awaitUntil { it.connection.problem != null }.connection

                assertThat(panel.visibleProblem?.kind).isEqualTo(ConnectionProblem.Kind.ADDRESS_REJECTED)
                assertThat(panel.visibleProblem?.detail).isEqualTo("Public addresses need HTTPS")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLocalNetworkDenialKeepsItsOwnBlockInsteadOfTheProblemNotice() =
        runTest {
            viewModel().uiState.test {
                piSession.linkState.value = PiLinkState.Idle
                shots.connectionState.value =
                    ConnectionState.Error("Local network prohibited", ConnectionErrorKind.LOCAL_NETWORK_DENIED)
                val panel = awaitUntil { it.connection.localNetworkDenied }.connection

                assertThat(panel.localNetworkDenied).isTrue()
                assertThat(panel.problem?.kind).isEqualTo(ConnectionProblem.Kind.LOCAL_NETWORK_DENIED)
                assertThat(panel.visibleProblem).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun noProblemWhileTheLiveSessionIsUp() =
        runTest {
            viewModel().uiState.test {
                shots.connectionState.value = ConnectionState.Error("HTTP 404")
                val panel = awaitUntil { it.connection.state is ConnectionState.Error }.connection
                assertThat(panel.problem == null).isTrue()
                piSession.linkState.value = PiLinkState.Idle
                assertThat(awaitUntil { it.connection.problem != null }.connection.problem?.kind)
                    .isEqualTo(ConnectionProblem.Kind.CONNECTION_FAILED)
                assertThat(panel.localNetworkDenied).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
