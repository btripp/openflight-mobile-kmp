// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.sensors.GravitySensor
import dev.openflight.companion.core.sensors.deviceModel
import dev.openflight.companion.core.sensors.orientationStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The calibration screen's state holder (RadarCalibrationView.swift). Sampling ([sensorState])
 * runs for the lifetime of this ViewModel independent of [submitState]: the reference keeps
 * averaging gravity while, and after, a submission is in flight.
 */
class CalibrationViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    gravitySensor: GravitySensor,
    /**
     * Defaults to the platform [deviceModel]. Injectable so host unit tests never touch the
     * Android actual, which reads `Build.MODEL` and returns null (an NPE through Kotlin's
     * non-null intrinsic) outside an instrumented/Robolectric environment.
     */
    deviceModelProvider: () -> String = ::deviceModel,
) : ViewModel() {
    /** The host field's text while the user edits it; `null` shows the saved host. */
    private val hostDraft = MutableStateFlow<String?>(null)
    private val submitState = MutableStateFlow<SubmitUiState>(SubmitUiState.Idle)

    private val sensorState: StateFlow<SensorUiState> =
        if (!gravitySensor.isAvailable) {
            MutableStateFlow(SensorUiState.Unavailable(MOTION_UNAVAILABLE_MESSAGE))
        } else {
            val mapped: Flow<SensorUiState> =
                gravitySensor
                    .samples()
                    .orientationStates(deviceModelProvider())
                    .map { state ->
                        SensorUiState.Sampling(
                            displayAngles = state.displayAngles,
                            progress = state.progress,
                            sampleCount = state.sampleCount,
                            measurement = state.measurement,
                        )
                    }
            mapped
                .catch { emit(SensorUiState.Unavailable(it.message ?: MOTION_UNAVAILABLE_MESSAGE)) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue =
                        SensorUiState.Sampling(
                            displayAngles = null,
                            progress = 0.0,
                            sampleCount = 0,
                            measurement = null,
                        ),
                )
        }

    private val panel = combine(settings.transport, settings.host, shots.supportsControls, ::Panel)

    val uiState: StateFlow<CalibrationUiState> =
        combine(sensorState, submitState, panel, hostDraft) { sensor, submit, saved, draft ->
            CalibrationUiState(
                sensor = sensor,
                submit = submit,
                transport = saved.transport,
                host = draft ?: saved.host,
                bluetoothReady = saved.bluetoothReady,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = CalibrationUiState.Initial,
        )

    fun onEvent(event: CalibrationEvent) {
        when (event) {
            is CalibrationEvent.HostEdited -> hostDraft.value = event.text
            CalibrationEvent.HostSubmitted -> submitHost()
            CalibrationEvent.Apply -> apply()
            CalibrationEvent.DismissResult -> submitState.value = SubmitUiState.Idle
        }
    }

    /** Mirrors the dashboard's host field: apply on submit, retry when the host is unchanged. */
    private fun submitHost() {
        viewModelScope.launch {
            val saved = settings.host.first()
            val submitted = (hostDraft.value ?: saved).trim()
            if (submitted == saved) {
                shots.retry()
            } else {
                settings.setHost(submitted)
            }
            hostDraft.value = null
        }
    }

    @Suppress("TooGenericExceptionCaught") // Shown verbatim in the Failed state, like the reference.
    private fun apply() {
        if (!uiState.value.applyEnabled) return
        val measurement = (sensorState.value as? SensorUiState.Sampling)?.measurement ?: return
        submitState.value = SubmitUiState.Submitting
        viewModelScope.launch {
            submitState.value =
                try {
                    SubmitUiState.Applied(shots.submitCalibration(measurement))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    SubmitUiState.Failed(failure.message ?: SUBMIT_FAILED_MESSAGE)
                }
        }
    }

    private data class Panel(
        val transport: TransportType,
        val host: String,
        val bluetoothReady: Boolean,
    )

    companion object {
        const val MOTION_UNAVAILABLE_MESSAGE = "Motion unavailable"
        const val SUBMIT_FAILED_MESSAGE = "Couldn't submit the calibration."
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
