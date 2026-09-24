// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.sensors.PhoneOrientationDisplayAngles

/**
 * What the calibration screen renders (RadarCalibrationView.swift). [sensor] and [submit] are
 * independent product-type fields, not a single sealed hierarchy: sampling never stops, including
 * while a submission is in flight or after one finishes (plan Step 8c).
 */
data class CalibrationUiState(
    val sensor: SensorUiState,
    val submit: SubmitUiState,
    val transport: TransportType,
    /** The host field's text: the user's unsubmitted edit, or else the saved host. */
    val host: String,
    /** Whether the active Bluetooth transport can carry control commands. Ignored on Wi-Fi. */
    val bluetoothReady: Boolean,
) {
    /**
     * Apply is enabled only when the measurement is send-ready, the active transport can carry
     * control commands, and nothing is already in flight (RadarCalibrationView's
     * `transportIsReady` combined with `isSending`).
     */
    val applyEnabled: Boolean
        get() {
            val measurement = (sensor as? SensorUiState.Sampling)?.measurement
            return measurement?.isReadyToSend == true &&
                (transport == TransportType.WIFI || bluetoothReady) &&
                submit !is SubmitUiState.Submitting
        }

    companion object {
        val Initial =
            CalibrationUiState(
                sensor =
                    SensorUiState.Sampling(
                        displayAngles = null,
                        progress = 0.0,
                        sampleCount = 0,
                        measurement = null,
                    ),
                submit = SubmitUiState.Idle,
                transport = TransportType.BLUETOOTH,
                host = "",
                bluetoothReady = false,
            )
    }
}

/** The gravity sensor's state, independent of any submission. */
sealed interface SensorUiState {
    /** No device motion (for example the iOS simulator): shows "Motion unavailable". */
    data class Unavailable(
        val message: String,
    ) : SensorUiState

    /** Sampling is running. [measurement] is null until the 120-sample window is send-ready. */
    data class Sampling(
        val displayAngles: PhoneOrientationDisplayAngles?,
        val progress: Double,
        val sampleCount: Int,
        val measurement: PhoneOrientationMeasurement?,
    ) : SensorUiState
}

/** The Apply button's request state. */
sealed interface SubmitUiState {
    data object Idle : SubmitUiState

    data object Submitting : SubmitUiState

    data class Applied(
        val result: CalibrationResult,
    ) : SubmitUiState

    /** [message] is the server's error verbatim when the transport carried one (for example a 409). */
    data class Failed(
        val message: String,
    ) : SubmitUiState
}
