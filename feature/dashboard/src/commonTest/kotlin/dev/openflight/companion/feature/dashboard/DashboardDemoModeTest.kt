// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.testing.FakeDemoModeRepository
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

/** Plan F14: "Try without a Pi", the card in Demo mode, Exit and Hit a shot. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardDemoModeTest {
    private val settings = FakeSettingsRepository(transport = TransportType.BLUETOOTH)
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository(PiLinkState.Idle)
    private val demo = FakeDemoModeRepository()

    private fun viewModel() = DashboardViewModel(shots, settings, piSession, ClubConfirmation(), demo)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun tryWithoutAPiIsOfferedOnlyWhileNothingIsConnected() {
        assertThat(ConnectionPanelState(state = ConnectionState.Idle).showTryDemo).isTrue()
        assertThat(ConnectionPanelState(state = ConnectionState.Error("refused")).showTryDemo).isTrue()
        assertThat(ConnectionPanelState(state = ConnectionState.Connected).showTryDemo).isFalse()
        assertThat(ConnectionPanelState(state = ConnectionState.Idle, piLinkConnected = true).showTryDemo).isFalse()
        assertThat(ConnectionPanelState(state = ConnectionState.Connected, demo = true).showTryDemo).isFalse()
    }

    @Test
    fun inDemoModeTheCardIsTheDemoPiWithNoHostFieldOrHelpLink() {
        val card =
            ConnectionPanelState(transport = TransportType.WIFI, state = ConnectionState.Connected, demo = true)
        assertThat(card.statusTitle).isEqualTo(ConnectionPanelState.DEMO_TITLE)
        assertThat(card.showHostField).isFalse()
        assertThat(card.hostHints.isEmpty()).isTrue()
        assertThat(card.helpLink).isNull()
    }

    @Test
    fun tryWithoutAPiTurnsDemoModeOnAndExitTurnsItOff() =
        runTest {
            val viewModel = viewModel()
            viewModel.uiState.test {
                assertThat(awaitItem().connection.demo).isFalse()

                viewModel.onEvent(DashboardEvent.TryDemo)
                assertThat(demo.enabled.value).isTrue()
                assertThat(awaitItem().connection.demo).isTrue()

                viewModel.onEvent(DashboardEvent.ExitDemo)
                assertThat(demo.enabled.value).isFalse()
                assertThat(awaitItem().connection.demo).isFalse()
            }
        }

    @Test
    fun hitAShotAsksThePretendPi() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(DashboardEvent.HitDemoShot)
            viewModel.onEvent(DashboardEvent.HitDemoShot)
            assertThat(demo.hitShotCalls).isEqualTo(2)
        }

    @Test
    fun demoModeAsksForNoTransportPermission() =
        runTest {
            val viewModel = viewModel()
            viewModel.selectedTransport.test {
                assertThat(awaitItem()).isEqualTo(TransportType.BLUETOOTH)
                demo.setEnabled(true)
                assertThat(awaitItem()).isNull()
                demo.setEnabled(false)
                assertThat(awaitItem()).isEqualTo(TransportType.BLUETOOTH)
            }
        }
}
