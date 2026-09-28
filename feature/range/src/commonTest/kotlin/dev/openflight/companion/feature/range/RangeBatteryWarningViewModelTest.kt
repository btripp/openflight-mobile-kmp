// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import dev.openflight.companion.core.model.pi.PiBatteryLevel
import dev.openflight.companion.core.model.pi.PowerState
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.testing.FakeConditionsRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeShotHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Issue #48 on the range: the Pi's low-battery notice in the overlay. */
@OptIn(ExperimentalCoroutinesApi::class)
class RangeBatteryWarningViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val settings = FakeSettingsRepository()
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(): DrivingRangeViewModel =
        DrivingRangeViewModel(
            shots = shots,
            settings = settings,
            history = FakeShotHistoryRepository(),
            conditions = FakeConditionsRepository(),
            piSession = piSession,
            flightPlan = { m, c, b -> testFlightPlan(m, c, b, ::makeTestTrajectory) },
            computeDispatcher = StandardTestDispatcher(scheduler),
            distanceEstimate = { _, _, _ -> null },
        )

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

    @Test
    fun aLowBatteryShowsOnTheReadyRangeAndPluggingInClearsIt() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                assertThat(awaitItem().batteryWarning).isNull()

                piSession.powerStatus.value = power(PowerState.LOW, 18.0)
                val warned = awaitUntil { it.batteryWarning != null }
                assertThat(warned).isInstanceOf<DrivingRangeUiState.Ready>()
                assertThat(warned.batteryWarning?.level).isEqualTo(PiBatteryLevel.LOW)
                assertThat(warned.batteryWarning?.title).isEqualTo("Pi battery low (18%)")

                piSession.powerStatus.value = power(PowerState.LOW, 18.0, externalPower = true)
                assertThat(awaitUntil { it.batteryWarning == null }.batteryWarning).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aCriticalBatteryShowsWhileAShotIsOnTheRange() =
        runTest(scheduler) {
            val viewModel = makeViewModel()

            viewModel.uiState.test {
                shots.emit(makeDrivingRangeShot())
                awaitUntil { it is DrivingRangeUiState.Showing }

                piSession.powerStatus.value = power(PowerState.CRITICAL, 9.0)
                val warned = awaitUntil { it.batteryWarning != null }
                assertThat(warned).isInstanceOf<DrivingRangeUiState.Showing>()
                assertThat(warned.batteryWarning?.title).isEqualTo("Pi battery critical (9%)")
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun ReceiveTurbine<DrivingRangeUiState>.awaitUntil(
        predicate: (DrivingRangeUiState) -> Boolean,
    ): DrivingRangeUiState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }
}
