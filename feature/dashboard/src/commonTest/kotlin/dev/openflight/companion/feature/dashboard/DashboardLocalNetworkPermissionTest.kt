// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.PiLinkState
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

/**
 * Plan R8f: Android 17's `ACCESS_LOCAL_NETWORK` denial, reported by the shell, maps to the same
 * actionable state as iOS's Local Network denial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardLocalNetworkPermissionTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository(PiLinkState.Reconnecting(1, 500, "timeout"))

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
    fun aDeniedPermissionShowsTheBlockInsteadOfTheTimeout() =
        runTest {
            val vm = viewModel()
            vm.uiState.test {
                shots.connectionState.value = ConnectionState.Error("timeout")
                assertThat(awaitUntil { it.connection.problem != null }.connection.localNetworkDenied).isFalse()

                vm.onEvent(DashboardEvent.LocalNetworkPermissionChanged(granted = false))
                val denied = awaitUntil { it.connection.localNetworkDenied }.connection
                // The timeout it causes isn't repeated as a separate problem.
                assertThat(denied.visibleProblem).isNull()

                vm.onEvent(DashboardEvent.LocalNetworkPermissionChanged(granted = true))
                assertThat(awaitUntil { !it.connection.localNetworkDenied }.connection.localNetworkDenied).isFalse()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun bluetoothNeverShowsTheLocalNetworkBlock() =
        runTest {
            val vm = viewModel()
            vm.uiState.test {
                vm.onEvent(DashboardEvent.LocalNetworkPermissionChanged(granted = false))
                assertThat(awaitUntil { it.connection.localNetworkDenied }.connection.localNetworkDenied).isTrue()

                settings.setTransport(TransportType.BLUETOOTH)
                assertThat(
                    awaitUntil { it.connection.transport == TransportType.BLUETOOTH }.connection.localNetworkDenied,
                ).isFalse()
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
