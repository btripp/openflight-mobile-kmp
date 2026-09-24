// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.sensors.GravitySensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI)
    private val shots = FakeShotRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(sensor: GravitySensor) =
        CalibrationViewModel(shots, settings, sensor, deviceModelProvider = { "Pixel 9" })

    @Test
    fun stableSamplesProduceAReadyMeasurement() =
        runTest {
            val vm = viewModel(FakeGravitySensor(flow = stableSamples()))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { (it.sensor as? SensorUiState.Sampling)?.measurement?.isReadyToSend == true }
                val sensor = state.sensor as SensorUiState.Sampling
                assertThat(sensor.measurement!!.isReadyToSend).isTrue()
                assertThat(state.applyEnabled).isTrue()
            }
        }

    @Test
    fun noisySamplesNeverBecomeReady() =
        runTest {
            val vm = viewModel(FakeGravitySensor(flow = alternatingSamples(noisyRollA, noisyRollB)))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { (it.sensor as? SensorUiState.Sampling)?.sampleCount == 120 }
                val sensor = state.sensor as SensorUiState.Sampling
                assertThat(sensor.measurement).isNotNull()
                assertThat(sensor.measurement!!.isReadyToSend).isFalse()
                assertThat(sensor.measurement.rollStddevDeg).isGreaterThan(0.5)
                assertThat(state.applyEnabled).isFalse()
            }
        }

    @Test
    fun rollBeyondThreeDegreesIsNotReady() =
        runTest {
            val vm = viewModel(FakeGravitySensor(flow = stableSamples(highRoll)))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { (it.sensor as? SensorUiState.Sampling)?.sampleCount == 120 }
                val sensor = state.sensor as SensorUiState.Sampling
                assertThat(sensor.measurement).isNotNull()
                assertThat(sensor.measurement!!.isReadyToSend).isFalse()
                assertThat(abs(sensor.measurement.rollDeg) > 3.0).isTrue()
            }
        }

    @Test
    fun bluetoothWithoutControlsDisablesApply() =
        runTest {
            settings.transport.value = TransportType.BLUETOOTH
            shots.supportsControls.value = false
            val vm = viewModel(FakeGravitySensor(flow = stableSamples()))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { (it.sensor as? SensorUiState.Sampling)?.measurement?.isReadyToSend == true }
                assertThat(state.applyEnabled).isFalse()

                shots.supportsControls.value = true
                val ready = awaitUntil { it.bluetoothReady }
                assertThat(ready.applyEnabled).isTrue()
            }
        }

    @Test
    fun wifiEnablesApplyWithoutBluetoothControls() =
        runTest {
            settings.transport.value = TransportType.WIFI
            val vm = viewModel(FakeGravitySensor(flow = stableSamples()))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { (it.sensor as? SensorUiState.Sampling)?.measurement?.isReadyToSend == true }
                assertThat(state.applyEnabled).isTrue()
            }
        }

    @Test
    fun applyingASuccessfulSubmissionShowsTheAppliedResult() =
        runTest {
            val vm = viewModel(FakeGravitySensor(flow = stableSamples()))
            vm.uiState.testIgnoringRest {
                awaitUntil { it.applyEnabled }
                vm.onEvent(CalibrationEvent.Apply)
                val applied = awaitUntil { it.submit is SubmitUiState.Applied }
                val submit = applied.submit as SubmitUiState.Applied
                assertThat(submit.result).isEqualTo(FakeShotRepository.DEFAULT_CALIBRATION_RESULT)
                assertThat(shots.submittedMeasurements).hasSize(1)
            }
        }

    @Test
    fun aFailedSubmissionShowsTheServerMessageVerbatim() =
        runTest {
            shots.submitCalibrationResponse = { error("TI IWR6843 radar is not enabled") }
            val vm = viewModel(FakeGravitySensor(flow = stableSamples()))
            vm.uiState.testIgnoringRest {
                awaitUntil { it.applyEnabled }
                vm.onEvent(CalibrationEvent.Apply)
                val failed = awaitUntil { it.submit is SubmitUiState.Failed }
                assertThat((failed.submit as SubmitUiState.Failed).message).isEqualTo("TI IWR6843 radar is not enabled")
            }
        }

    @Test
    fun samplingContinuesWhileAndAfterASubmission() =
        runTest {
            val vm = viewModel(FakeGravitySensor(flow = stableSamples(count = 240)))
            vm.uiState.testIgnoringRest {
                awaitUntil { it.applyEnabled }
                vm.onEvent(CalibrationEvent.Apply)
                val applied = awaitUntil { it.submit is SubmitUiState.Applied }
                val sensor = applied.sensor as SensorUiState.Sampling
                assertThat(sensor.measurement).isNotNull()
                assertThat(sensor.measurement!!.isReadyToSend).isTrue()
            }
        }

    @Test
    fun anUnavailableSensorReportsMotionUnavailable() =
        runTest {
            val vm = viewModel(FakeGravitySensor(isAvailable = false))
            vm.uiState.testIgnoringRest {
                val state = awaitUntil { it.sensor is SensorUiState.Unavailable }
                assertThat((state.sensor as SensorUiState.Unavailable).message)
                    .isEqualTo(CalibrationViewModel.MOTION_UNAVAILABLE_MESSAGE)
            }
        }

    /** Like `test`, but tolerates the extra intermediate states `combine` may emit after the assertions. */
    private suspend fun <T> Flow<T>.testIgnoringRest(block: suspend ReceiveTurbine<T>.() -> Unit) =
        test {
            block()
            cancelAndIgnoreRemainingEvents()
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
