// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.calibration

/** User intents from the calibration screen, sent up to [CalibrationViewModel.onEvent]. */
sealed interface CalibrationEvent {
    /** A keystroke in the Wi-Fi host field. Local only: nothing changes until [HostSubmitted]. */
    data class HostEdited(
        val text: String,
    ) : CalibrationEvent

    /** The host field's Go/Done action: persist the host, or retry the connection when unchanged. */
    data object HostSubmitted : CalibrationEvent

    /** "Apply Calibration" tapped. A no-op unless [CalibrationUiState.applyEnabled] is true. */
    data object Apply : CalibrationEvent

    /** Dismisses the last Applied/Failed result, returning [CalibrationUiState.submit] to Idle. */
    data object DismissResult : CalibrationEvent
}
