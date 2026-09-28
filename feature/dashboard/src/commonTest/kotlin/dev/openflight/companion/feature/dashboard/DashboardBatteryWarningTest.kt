// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.pi.PiBatteryLevel
import dev.openflight.companion.core.model.pi.PiBatteryWarning
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
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

/** Issue #48 on the dashboard: the Pi's low-battery notice. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardBatteryWarningTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()

    private fun viewModel() = DashboardViewModel(shots, settings, piSession, ClubConfirmation())

    private fun power(
        state: PowerState,
        percent: Double,
        externalPower: Boolean = false,
    ) = PowerStatus(
        available = true,
        provider = "geekworm",
        state = state,
        batteryPercent = percent,
        externalPower = externalPower,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noPowerStatusMeansNoWarning() =
        runTest {
            viewModel().uiState.test {
                assertThat(awaitItem().batteryWarning).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aLowBatteryShowsTheWarningWhileWaiting() =
        runTest {
            viewModel().uiState.test {
                piSession.powerStatus.value = power(PowerState.LOW, 18.0)
                val state = awaitUntil { it.batteryWarning != null }

                assertThat(state).isInstanceOf<DashboardUiState.Waiting>()
                assertThat(state.batteryWarning?.level).isEqualTo(PiBatteryLevel.LOW)
                assertThat(state.batteryWarning?.title).isEqualTo("Pi battery low (18%)")
                assertThat(state.batteryWarning?.detail).isEqualTo(PiBatteryWarning.LOW_DETAIL)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aCriticalBatteryShowsTheWarningWithAShot() =
        runTest {
            viewModel().uiState.test {
                shots.history.value = listOf(shot(1))
                piSession.powerStatus.value = power(PowerState.CRITICAL, 9.0)
                val state = awaitUntil { it is DashboardUiState.Live && it.batteryWarning != null }

                assertThat(state.batteryWarning?.title).isEqualTo("Pi battery critical (9%)")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun pluggingInClearsTheWarning() =
        runTest {
            viewModel().uiState.test {
                piSession.powerStatus.value = power(PowerState.LOW, 18.0)
                awaitUntil { it.batteryWarning != null }

                piSession.powerStatus.value = power(PowerState.LOW, 18.0, externalPower = true)
                assertThat(awaitUntil { it.batteryWarning == null }.batteryWarning).isNull()

                piSession.powerStatus.value = power(PowerState.PLUGGED_IN, 19.0, externalPower = true)
                expectNoEvents()
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
