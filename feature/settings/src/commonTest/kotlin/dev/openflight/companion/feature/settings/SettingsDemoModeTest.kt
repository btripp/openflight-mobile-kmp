// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.testing.FakeDemoModeRepository
import dev.openflight.companion.core.testing.FakePiSessionRepository
import dev.openflight.companion.core.testing.FakeScreenReaderMonitor
import dev.openflight.companion.core.testing.FakeSettingsRepository
import dev.openflight.companion.core.testing.FakeShotRepository
import dev.openflight.companion.core.testing.FakeSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Plan F14: Settings › Device › Demo mode: the switch, automatic shots and "Clear demo data". */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDemoModeTest {
    private val demo = FakeDemoModeRepository()
    private lateinit var viewModel: SettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel =
            SettingsViewModel(
                FakeShotRepository(),
                FakeSettingsRepository(),
                FakePiSessionRepository(),
                AppLifecycle().apply { onForeground() },
                FakeSpeechEngine(),
                FakeScreenReaderMonitor(),
                demo,
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun theSwitchTurnsDemoModeOnAndOff() =
        runTest {
            viewModel.uiState.test {
                assertThat(awaitItem().demo.enabled).isFalse()

                viewModel.onEvent(SettingsEvent.SetDemoMode(true))
                assertThat(awaitItem().demo.enabled).isTrue()

                viewModel.onEvent(SettingsEvent.SetDemoMode(false))
                assertThat(awaitItem().demo.enabled).isFalse()
            }
        }

    @Test
    fun automaticShotsTakeOnlyTheOfferedIntervals() =
        runTest {
            viewModel.uiState.test {
                assertThat(awaitItem().demo.autoFireSeconds).isEqualTo(0)

                viewModel.onEvent(SettingsEvent.SetDemoAutoFire(10))
                assertThat(awaitItem().demo.autoFireSeconds).isEqualTo(10)

                viewModel.onEvent(SettingsEvent.SetDemoAutoFire(3))
                expectNoEvents()
            }
            assertThat(DemoSettingsUiState.autoFireLabel(0)).isEqualTo("Off")
            assertThat(DemoSettingsUiState.autoFireLabel(10)).isEqualTo("Every 10 s")
        }

    @Test
    fun clearingDemoDataIsConfirmedFirstThenReported() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            viewModel.effects.test {
                viewModel.onEvent(SettingsEvent.ConfirmClearDemoData)
                assertThat(demo.clearCalls).isEqualTo(0)

                viewModel.onEvent(SettingsEvent.RequestClearDemoData)
                assertThat(viewModel.uiState.value.demo.confirmingClear).isTrue()
                viewModel.onEvent(SettingsEvent.CancelClearDemoData)
                assertThat(viewModel.uiState.value.demo.confirmingClear).isFalse()
                assertThat(demo.clearCalls).isEqualTo(0)

                viewModel.onEvent(SettingsEvent.RequestClearDemoData)
                viewModel.onEvent(SettingsEvent.ConfirmClearDemoData)
                assertThat(demo.clearCalls).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(SettingsEffect.Message(DemoSettingsUiState.CLEARED_MESSAGE))
                assertThat(viewModel.uiState.value.demo.confirmingClear).isFalse()
            }
        }
}
